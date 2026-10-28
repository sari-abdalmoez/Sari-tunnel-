package africa.saritunnel

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Loads vless:// profiles from the Blogger post, keeps them (with measured latency and connection
 * results) in a local JSON file, and never discards the cache when the network fails.
 */
class VlessSubscriptionManager(context: Context) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "vless_servers.json")

    data class Snapshot(
        val servers: List<VlessServer>,
        val fetchedAt: Long,
        val lastAttemptAt: Long,
        val lastError: String?
    )

    sealed class RefreshResult {
        data class Ok(val count: Int) : RefreshResult()
        data class Error(val message: String) : RefreshResult()
    }

    init {
        // Older builds cached links from unrelated GitHub feeds; those must never be shown again.
        runCatching { File(appContext.cacheDir, "vless_profiles").deleteRecursively() }
        runCatching { appContext.getSharedPreferences("vless_subscriptions", Context.MODE_PRIVATE).edit().clear().apply() }
    }

    fun loadSnapshot(): Snapshot = synchronized(LOCK) { readSnapshot() }

    /** True when the cache is empty or older than 24 hours (and we did not just try). */
    fun shouldRefresh(snapshot: Snapshot = loadSnapshot(), now: Long = System.currentTimeMillis()): Boolean {
        val recentlyTried = now - snapshot.lastAttemptAt < RETRY_BACKOFF_MS
        if (recentlyTried) return false
        return snapshot.servers.isEmpty() || now - snapshot.fetchedAt >= REFRESH_INTERVAL_MS
    }

    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val html = download(SOURCE_URL)
            val parsed = parsePage(html)
            if (parsed.isEmpty()) {
                val msg = "No valid vless:// links were found on the source page."
                markAttempt(now, msg)
                return@withContext RefreshResult.Error(msg)
            }
            val previous = synchronized(LOCK) { readSnapshot().servers.associateBy { it.id } }
            var geoBudget = MAX_GEO_LOOKUPS
            val merged = parsed.map { fresh ->
                val old = previous[fresh.id]
                var code = fresh.countryCode
                if (code.isEmpty() && old != null && old.countryCode.isNotEmpty()) code = old.countryCode
                if (code.isEmpty() && geoBudget > 0) {
                    geoBudget--
                    code = lookupCountryCode(fresh.host).orEmpty()
                }
                val withCountry = if (code.isNotEmpty())
                    fresh.copy(countryCode = code, country = CountryUtils.displayName(code)) else fresh
                if (old == null) withCountry else withCountry.copy(
                    reachable = old.reachable, pingMs = old.pingMs, testedAt = old.testedAt,
                    okCount = old.okCount, failCount = old.failCount, lastConnectOk = old.lastConnectOk
                )
            }
            synchronized(LOCK) { write(Snapshot(merged, now, now, null)) }
            RefreshResult.Ok(merged.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            markAttempt(now, msg)
            RefreshResult.Error(msg)
        }
    }

    /** Applies endpoint latency results (key = host:port) to every server on that endpoint. */
    fun saveLatency(results: Map<String, LatencyTester.Result>) {
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(LOCK) {
            val snap = readSnapshot()
            val updated = snap.servers.map { s ->
                val r = results[s.endpointKey] ?: return@map s
                s.copy(reachable = r.reachable, pingMs = r.pingMs, testedAt = now)
            }
            write(snap.copy(servers = updated))
        }
    }

    /** Records the outcome of a real connection attempt (used by "best performing" sorting). */
    fun recordConnection(serverId: String?, ok: Boolean) {
        if (serverId.isNullOrBlank()) return
        synchronized(LOCK) {
            val snap = readSnapshot()
            val updated = snap.servers.map { s ->
                if (s.id != serverId) s else s.copy(
                    okCount = s.okCount + if (ok) 1 else 0,
                    failCount = s.failCount + if (ok) 0 else 1,
                    lastConnectOk = ok
                )
            }
            write(snap.copy(servers = updated))
        }
    }

    // ---------------------------------------------------------------- network

    private fun download(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("Source page returned HTTP $code")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PAGE_BYTES) throw java.io.IOException("Source page is too large")
                    out.write(buffer, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Best-effort country lookup for servers whose country cannot be read from the page. */
    private fun lookupCountryCode(host: String): String? {
        return try {
            val ip = InetAddress.getByName(host).hostAddress ?: return null
            val conn = URL("https://ipwho.is/$ip?fields=success,country_code").openConnection() as HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader().use { it.readText().take(2_000) }
                val json = JSONObject(body)
                val code = json.optString("country_code").uppercase(Locale.US)
                if (json.optBoolean("success", false) && CountryUtils.isValid(code)) code else null
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- parsing

    /**
     * The post lists a country name on the line above each vless:// link. Text between two links is
     * therefore the label of the second link, which also works if the markup collapses newlines.
     */
    internal fun parsePage(html: String): List<VlessServer> {
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript").remove()
        doc.select("a[href^=\"vless://\"]").forEach { a -> a.text(a.attr("href")) }
        doc.select("br").after("\n")
        doc.select("p, div, li, pre, h1, h2, h3, h4, h5, h6, tr").before("\n")
        val post = doc.selectFirst(".post-body, .entry-content, article")
        if (post != null) {
            val fromPost = extract(post.wholeText())
            if (fromPost.isNotEmpty()) return fromPost
        }
        return extract(doc.body().wholeText())
    }

    private fun extract(text: String): List<VlessServer> {
        val found = LinkedHashMap<String, VlessServer>()
        var cursor = 0
        for (match in LINK.findAll(text)) {
            val label = labelFrom(text.substring(cursor, match.range.first))
            cursor = match.range.last + 1
            val link = match.value.trimEnd(',', ';', ')', ']', '}', '.', '"', '\'')
            if (link.contains("...") || link.contains('\u2026')) continue // truncated preview text
            val server = buildServer(link, label) ?: continue
            found.putIfAbsent(server.uri, server)
        }
        return found.values.toList()
    }

    private fun labelFrom(segment: String): String? {
        val line = segment.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        val cleaned = line.trim(' ', '-', '\u2013', '\u2022', '*', ':', '|')
        return cleaned.takeIf { it.length in 1..60 }
    }

    private fun buildServer(link: String, label: String?): VlessServer? {
        val uri = try { Uri.parse(link) } catch (_: Exception) { return null }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        if (port !in 1..65535) return null
        try { XrayConfigBuilder.fromVlessUri(link) } catch (_: Exception) { return null }

        val fragment = uri.fragment?.trim().orEmpty()
        val name = fragment.replace(Regex("\\s+by\\s+\\S+$", RegexOption.IGNORE_CASE), "").trim()
            .ifBlank { "$host:$port" }
        val code = CountryUtils.detectFromLabel(label)
            ?: CountryUtils.detectFromName(fragment)
            ?: CountryUtils.detectFromHost(host)
            ?: ""
        val security = (uri.getQueryParameter("security") ?: "none").uppercase(Locale.ROOT)
        val type = (uri.getQueryParameter("type") ?: "tcp").let { if (it.equals("raw", true)) "tcp" else it }
            .uppercase(Locale.ROOT)
        return VlessServer(
            id = sha1(link).take(12),
            name = name,
            uri = link,
            host = host,
            port = port,
            country = CountryUtils.displayName(code),
            countryCode = code,
            transport = if (security == "NONE") type else "$security · $type"
        )
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- storage

    private fun readSnapshot(): Snapshot {
        return try {
            if (!cacheFile.exists()) return EMPTY
            val root = JSONObject(cacheFile.readText())
            val arr = root.optJSONArray("servers") ?: JSONArray()
            val list = ArrayList<VlessServer>(arr.length())
            for (i in 0 until arr.length()) VlessServer.fromJson(arr.getJSONObject(i))?.let { list.add(it) }
            Snapshot(
                list,
                root.optLong("fetchedAt", 0L),
                root.optLong("lastAttemptAt", 0L),
                root.optString("lastError").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            EMPTY
        }
    }

    private fun write(snapshot: Snapshot) {
        try {
            val root = JSONObject()
                .put("version", 2)
                .put("source", SOURCE_URL)
                .put("fetchedAt", snapshot.fetchedAt)
                .put("lastAttemptAt", snapshot.lastAttemptAt)
                .put("lastError", snapshot.lastError ?: "")
                .put("servers", JSONArray().also { a -> snapshot.servers.forEach { a.put(it.toJson()) } })
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            // Keep running; the in-memory list is still usable.
        }
    }

    /** Records a failed attempt while keeping the previously cached servers untouched. */
    private fun markAttempt(now: Long, error: String) {
        synchronized(LOCK) { write(readSnapshot().copy(lastAttemptAt = now, lastError = error)) }
    }

    companion object {
        const val SOURCE_URL = "https://connection7876.blogspot.com/2026/10/blog-post.html"
        const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val RETRY_BACKOFF_MS = 10L * 60 * 1000
        private const val MAX_PAGE_BYTES = 5 * 1024 * 1024
        private const val MAX_GEO_LOOKUPS = 30
        private val LOCK = Any()
        private val EMPTY = Snapshot(emptyList(), 0L, 0L, null)
        private val LINK = Regex("vless://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
    }
}
