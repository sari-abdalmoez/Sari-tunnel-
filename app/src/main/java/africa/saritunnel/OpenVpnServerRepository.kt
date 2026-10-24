package africa.saritunnel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A public listing entry. Inline profiles are kept only for user-owned/imported configs. */
data class OpenVpnServer(
    val id: String,
    val name: String,
    val country: String,
    val ip: String,
    val profileUrl: String? = null,
    val inlineProfile: String? = null,
    val reportedPingMs: Int? = null
) {
    val displayName: String get() = if (country.isBlank() || country == "Custom") name else "$country · $ip"
}

/**
 * Discovers profiles from the ordinary IPSpeed HTML page and falls back to an HTTPS JSON feed.
 * This intentionally does not automate or evade Cloudflare/Turnstile challenges.
 */
object OpenVpnServerRepository {
    const val IPSPEED_URL = "https://ipspeed.info/free-openvpn.php"
    const val DEFAULT_FEED_URL = "https://raw.githubusercontent.com/sari-abdalmoez/Sari-tunnel-/main/servers.json"

    private const val MAX_HTML_BYTES = 2 * 1024 * 1024
    private const val MAX_JSON_BYTES = 512 * 1024
    private const val MAX_PROFILE_BYTES = 1_000_000
    private const val LIST_CACHE_MS = 10 * 60 * 1000L
    private const val IPSPEED_DOWNLOAD_GAP_MS = 10_500L
    private val IPV4_PATTERN = Regex("(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
    private val downloadMutex = Mutex()
    private val profileCache = ConcurrentHashMap<String, String>()
    @Volatile private var lastIpspeedDownloadAt = 0L
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedPublicServers: List<OpenVpnServer> = emptyList()

    suspend fun loadServers(context: Context, forceRefresh: Boolean = false): List<OpenVpnServer> =
        withContext(Dispatchers.IO) {
            val custom = customServer(context)
            val now = System.currentTimeMillis()
            if (!forceRefresh && cachedPublicServers.isNotEmpty() && now - cachedAt < LIST_CACHE_MS) {
                return@withContext (custom + cachedPublicServers).distinctBy { it.id }
            }

            var pageFailure = ""
            val scraped = try {
                scrapeIpSpeed()
            } catch (e: Exception) {
                pageFailure = safeMessage(e)
                emptyList()
            }
            if (scraped.isNotEmpty()) {
                cachedPublicServers = sortByReportedPing(scraped)
                cachedAt = now
                return@withContext (custom + cachedPublicServers).distinctBy { it.id }
            }

            val feedUrl = ConfigStore.feedUrl(context).ifBlank { DEFAULT_FEED_URL }
            var feedFailure = ""
            val fromFeed = try {
                loadJsonFeed(feedUrl)
            } catch (e: Exception) {
                feedFailure = safeMessage(e)
                emptyList()
            }
            if (fromFeed.isNotEmpty()) {
                cachedPublicServers = sortByReportedPing(fromFeed)
                cachedAt = now
                return@withContext (custom + cachedPublicServers).distinctBy { it.id }
            }

            if (cachedPublicServers.isNotEmpty() && now - cachedAt < 24 * 60 * 60 * 1000L) {
                return@withContext (custom + cachedPublicServers).distinctBy { it.id }
            }
            if (custom.isNotEmpty()) return@withContext custom
            throw IOException(
                "No server list is available. IPSpeed: ${pageFailure.ifBlank { "no .ovpn links were found" }}. " +
                    "Fallback feed: ${feedFailure.ifBlank { "the feed contains no usable HTTPS .ovpn entries" }}"
            )
        }

    suspend fun downloadProfile(server: OpenVpnServer): String = withContext(Dispatchers.IO) {
        server.inlineProfile?.let { return@withContext it }
        val address = server.profileUrl ?: throw IOException("This server has no download URL.")
        profileCache[address]?.let { return@withContext it }
        val url = try { URL(address) } catch (_: Exception) { throw IOException("The profile URL is invalid.") }
        if (url.protocol != "https") throw IOException("Profile downloads must use HTTPS.")

        val fromIpSpeed = isIpSpeedHost(url.host)
        if (fromIpSpeed) downloadMutex.lock()
        try {
            if (fromIpSpeed) {
                val waitMs = IPSPEED_DOWNLOAD_GAP_MS - (System.currentTimeMillis() - lastIpspeedDownloadAt)
                if (waitMs > 0) delay(waitMs)
                lastIpspeedDownloadAt = System.currentTimeMillis()
            }
            val text = getText(address, MAX_PROFILE_BYTES, "text/plain,application/octet-stream,*/*;q=0.8")
            if (!text.contains("remote", ignoreCase = true) || text.contains("<html", ignoreCase = true)) {
                throw IOException("The URL did not return an OpenVPN profile. The server may be offline or protecting downloads.")
            }
            OpenVpnConfigParser.prepare(text) // validate before handing it to the external engine
            profileCache[address] = text
            text
        } finally {
            if (fromIpSpeed) downloadMutex.unlock()
        }
    }

    private fun customServer(context: Context): List<OpenVpnServer> {
        val raw = ConfigStore.customProfile(context).takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val parsed = OpenVpnConfigParser.prepare(raw)
            listOf(OpenVpnServer(
                id = "custom-profile",
                name = "My custom profile",
                country = "Custom",
                ip = parsed.host,
                inlineProfile = raw,
                reportedPingMs = null
            ))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun scrapeIpSpeed(): List<OpenVpnServer> {
        val html = getText(IPSPEED_URL, MAX_HTML_BYTES, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (html.contains("cf-chl-", ignoreCase = true) ||
            html.contains("Checking your browser", ignoreCase = true) ||
            html.contains("Verify you are human", ignoreCase = true) ||
            html.contains("Just a moment", ignoreCase = true)
        ) {
            throw IOException("IPSpeed is showing a browser verification page; using the JSON fallback instead.")
        }
        val document = Jsoup.parse(html, IPSPEED_URL)
        val output = LinkedHashMap<String, OpenVpnServer>()
        for (anchor in document.select("a[href]")) {
            val href = anchor.attr("href").trim()
            val label = anchor.text().trim().ifBlank { href.substringAfterLast('/') }
            if (!href.substringBefore('?').endsWith(".ovpn", ignoreCase = true) &&
                !label.endsWith(".ovpn", ignoreCase = true)
            ) continue
            val absolute = normalizeIpSpeedUrl(href) ?: continue
            val row = anchor.closest("tr")
            val cells = row?.select("td").orEmpty()
            val country = cells.getOrNull(1)?.text()?.trim().orEmpty().take(40)
            val fileName = URL(absolute).path.substringAfterLast('/')
            val ip = extractIp(fileName) ?: extractIp(label) ?: "Unknown IP"
            val pingText = cells.lastOrNull()?.text().orEmpty()
            val ping = Regex("\\d+").find(pingText)?.value?.toIntOrNull()
            val id = absolute.substringAfterLast('/').lowercase(Locale.US)
            output.putIfAbsent(id, OpenVpnServer(
                id = id,
                name = label.removeSuffix(".ovpn").take(80),
                country = country,
                ip = ip,
                profileUrl = absolute,
                reportedPingMs = ping
            ))
            if (output.size >= 150) break
        }
        return output.values.toList()
    }

    private fun loadJsonFeed(feedUrl: String): List<OpenVpnServer> {
        val parsedUrl = try { URL(feedUrl) } catch (_: Exception) { throw IOException("The backup feed URL is invalid.") }
        if (parsedUrl.protocol != "https") throw IOException("The backup server feed must use HTTPS.")
        val json = getText(feedUrl, MAX_JSON_BYTES, "application/json,text/plain,*/*;q=0.8")
        val rootObject = if (json.trimStart().startsWith("{")) JSONObject(json) else null
        val entries = if (rootObject == null) JSONArray(json) else rootObject.optJSONArray("servers") ?: JSONArray()
        val result = LinkedHashMap<String, OpenVpnServer>()
        for (i in 0 until minOf(entries.length(), 150)) {
            val item = entries.optJSONObject(i) ?: continue
            val name = item.optString("name", "Server ${i + 1}").take(80)
            val country = item.optString("country", "Unknown").take(40)
            val rawIp = item.optString("ip", "").trim()
            val link = item.optString("url", item.optString("profileUrl", item.optString("configUrl", ""))).trim()
            val inline = item.optString("config", "").takeIf { it.contains("remote", ignoreCase = true) && it.length < MAX_PROFILE_BYTES }
            if (link.isBlank() && inline == null) continue
            if (link.isNotBlank()) {
                val u = try { URL(link) } catch (_: Exception) { continue }
                if (u.protocol != "https" || !u.path.endsWith(".ovpn", ignoreCase = true)) continue
            }
            val id = item.optString("id", link.ifBlank { "feed-profile-$i" }).take(200)
            result.putIfAbsent(id, OpenVpnServer(
                id = id,
                name = name,
                country = country,
                ip = rawIp.ifBlank { extractIp(link) ?: "Unknown IP" },
                profileUrl = link.ifBlank { null },
                inlineProfile = inline,
                reportedPingMs = item.optInt("ping", -1).takeIf { it >= 0 }
            ))
        }
        // IP-only entries are directory information, not connectable VPN profiles.
        // They are displayed with a disabled CONNECT button until a real profile is published.
        val addresses = rootObject?.optJSONArray("addresses") ?: JSONArray()
        for (i in 0 until minOf(addresses.length(), 150)) {
            val item = addresses.optJSONObject(i) ?: continue
            val ip = item.optString("ip", "").trim()
            if (!IPV4_PATTERN.matches(ip)) continue
            val country = item.optString("country", "Unknown").take(40)
            val name = item.optString("name", country).take(80)
            val id = item.optString("id", "ip-only:$ip").take(200)
            result.putIfAbsent(id, OpenVpnServer(
                id = id,
                name = name,
                country = country,
                ip = ip,
                profileUrl = null,
                inlineProfile = null,
                reportedPingMs = null
            ))
        }
        return result.values.toList()
    }

    private fun normalizeIpSpeedUrl(href: String): String? {
        return try {
            val resolved = URL(URL(IPSPEED_URL), href)
            if (!isIpSpeedHost(resolved.host)) return null
            if (!resolved.path.endsWith(".ovpn", ignoreCase = true)) return null
            val suffix = if (resolved.query.isNullOrBlank()) "" else "?${resolved.query}"
            "https://ipspeed.info${resolved.path}$suffix"
        } catch (_: Exception) {
            null
        }
    }

    private fun isIpSpeedHost(host: String): Boolean =
        host.equals("ipspeed.info", true) || host.equals("www.ipspeed.info", true) || host.equals("m.ipspeed.info", true)

    private fun extractIp(text: String): String? =
        Regex("(?<!\\d)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\d)")
            .find(text)?.value

    private fun sortByReportedPing(input: List<OpenVpnServer>): List<OpenVpnServer> =
        input.sortedWith(compareBy<OpenVpnServer>({ it.reportedPingMs ?: Int.MAX_VALUE }, { it.country }, { it.ip }))

    private fun getText(url: String, maxBytes: Int, accept: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_CHROME_UA)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = when (response.code) {
                    403, 429 -> "the website denied or rate-limited the request (HTTP ${response.code})"
                    else -> "HTTP ${response.code}"
                }
                throw IOException("Could not download source: $detail")
            }
            val body = response.body ?: throw IOException("The server returned an empty response.")
            val stream = body.byteStream()
            val out = ByteArrayOutputStream()
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) throw IOException("The server response exceeded the size limit.")
                    out.write(buffer, 0, count)
                }
            }
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private fun safeMessage(e: Exception): String = e.message?.take(180) ?: e.javaClass.simpleName

    private const val MOBILE_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
}
