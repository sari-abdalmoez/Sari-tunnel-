package africa.saritunnel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Downloads public .ovpn profiles and hands them to a compatible OpenVPN client.
 * This does not use the existing WireGuard engine to connect OpenVPN profiles. */
class OpenVpnCatalogActivity : AppCompatActivity() {
    private val sourceUrl = "https://ipspeed.info/free-openvpn.php"
    private val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout

    private data class Profile(val label: String, val country: String, val ping: String, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Free OpenVPN profiles"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val intro = TextView(this).apply {
            text = "Public profiles from IPSpeed. Server uptime and ping can change. Downloading a profile requires a compatible OpenVPN client."
            textSize = 14f
        }
        status = TextView(this).apply { text = "Ready"; textSize = 14f; setPadding(0, dp(12), 0, dp(8)) }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this@OpenVpnCatalogActivity).apply {
            text = "Refresh profiles"
            setOnClickListener { loadProfiles() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "Open website"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl))) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(intro)
        root.addView(actions)
        root.addView(progress)
        root.addView(status)
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadProfiles()
    }

    private fun loadProfiles() {
        progress.visibility = View.VISIBLE
        status.text = "Loading server list…"
        list.removeAllViews()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchProfiles() } }
            progress.visibility = View.GONE
            val profiles = result.getOrNull().orEmpty()
            if (profiles.isEmpty()) {
                status.text = if (result.isFailure) {
                    "Could not load profiles: ${result.exceptionOrNull()?.message ?: "network error"}. Open the website to check whether it requires a browser verification."
                } else {
                    "The page loaded, but no downloadable .ovpn links were found. Try Open website."
                }
                return@launch
            }
            status.text = "Found ${profiles.size} profiles. Downloads are limited by the source website."
            profiles.forEach { addProfile(it) }
        }
    }

    private fun fetchProfiles(): List<Profile> {
        val conn = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        }
        val html: String
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Source returned HTTP $code")
            html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2_000_000) }
        } finally { conn.disconnect() }

        val results = LinkedHashMap<String, Profile>()
        val rowRegex = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>")
        val hrefRegex = Regex("(?is)href\\s*=\\s*(['\"])(.*?)\\1")
        for (rowMatch in rowRegex.findAll(html)) {
            val row = rowMatch.groupValues[1]
            val cells = Regex("(?is)<td\\b[^>]*>(.*?)</td>").findAll(row)
                .map { cleanHtml(it.groupValues[1]) }.toList()
            val href = hrefRegex.findAll(row).firstOrNull { isOvpnHref(it.groupValues[2]) }
                ?.groupValues?.get(2) ?: continue
            val absolute = safeProfileUrl(href) ?: continue
            val anchorText = Regex("(?is)<a\\b[^>]*>(.*?)</a>").find(row)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
            val fileName = absolute.substringAfterLast('/').substringBefore('?')
            val country = cells.getOrNull(1).orEmpty().ifBlank { "Unknown country" }
            val ping = cells.lastOrNull()?.takeIf { it.contains("ms", true) || it == "-" }.orEmpty()
            val label = anchorText.ifBlank { fileName }.ifBlank { "OpenVPN profile" }
            results.putIfAbsent(absolute, Profile(label.take(100), country.take(60), ping.take(30), absolute))
        }
        // Fallback for pages that list links outside table rows.
        if (results.isEmpty()) {
            hrefRegex.findAll(html).forEach { match ->
                val href = match.groupValues[2]
                if (!isOvpnHref(href)) return@forEach
                val absolute = safeProfileUrl(href) ?: return@forEach
                val label = cleanHtml(href.substringAfterLast('/')).ifBlank { "OpenVPN profile" }
                results.putIfAbsent(absolute, Profile(label.take(100), "Unknown country", "", absolute))
            }
        }
        return results.values.take(100)
    }

    private fun isOvpnHref(raw: String): Boolean {
        val decoded = decodeHtml(raw.trim())
        val path = runCatching { URL(URL(sourceUrl), decoded).path }.getOrNull() ?: decoded.substringBefore('?')
        return path.endsWith(".ovpn", ignoreCase = true)
    }

    private fun safeProfileUrl(raw: String): String? {
        val decoded = decodeHtml(raw.trim())
        val url = runCatching { URL(URL(sourceUrl), decoded) }.getOrNull() ?: return null
        val host = url.host.lowercase(Locale.ROOT)
        if (url.protocol != "https") return null
        if (host != "ipspeed.info" && host != "m.ipspeed.info" && !host.endsWith(".ipspeed.info")) return null
        if (!url.path.endsWith(".ovpn", ignoreCase = true)) return null
        return runCatching { url.toURI().toASCIIString() }.getOrNull()
    }

    private fun addProfile(profile: Profile) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F6F8.toInt()); cornerRadius = dp(12).toFloat()
            }
        }
        card.addView(TextView(this).apply {
            text = profile.label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "${profile.country}${if (profile.ping.isNotBlank()) " · Ping ${profile.ping}" else ""}"
            textSize = 13f
        })
        card.addView(TextView(this).apply { text = profile.url; textSize = 10f })
        card.addView(Button(this).apply {
            text = "Download & import"
            setOnClickListener { downloadAndShare(profile) }
        })
        list.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    private fun downloadAndShare(profile: Profile) {
        progress.visibility = View.VISIBLE
        status.text = "Downloading ${profile.label}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // IPSpeed asks clients to leave at least ten seconds between profile downloads.
                    val prefs = getSharedPreferences("ovpn_catalog", MODE_PRIVATE)
                    val last = prefs.getLong("last_download_at", 0L)
                    val wait = 10_000L - (System.currentTimeMillis() - last)
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                    val conn = (URL(profile.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12_000
                        readTimeout = 15_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", userAgent)
                        setRequestProperty("Accept", "application/octet-stream,text/plain,*/*")
                    }
                    val text: String
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) throw IllegalStateException("Profile URL returned HTTP $code")
                        val bytes = conn.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (out.size() + n > 1_000_000) throw IllegalStateException("Profile is too large")
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                        text = String(bytes, Charsets.UTF_8)
                    } finally { conn.disconnect() }
                    if (!Regex("(?im)^\\s*(client|remote)\\b").containsMatchIn(text) || text.contains("<html", true)) {
                        throw IllegalStateException("The server returned a webpage or invalid profile, not an .ovpn configuration")
                    }
                    prefs.edit().putLong("last_download_at", System.currentTimeMillis()).apply()
                    val safeName = profile.label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(70).ifBlank { "server" }
                    val dir = File(cacheDir, "ovpn_profiles").apply { mkdirs() }
                    File(dir, "$safeName.ovpn").apply { writeText(text, Charsets.UTF_8) }
                }
            }
            progress.visibility = View.GONE
            val file = result.getOrNull()
            if (file == null) {
                status.text = "Download failed: ${result.exceptionOrNull()?.message ?: "unknown error"}. Try another server or open the source website."
                return@launch
            }
            status.text = "Profile downloaded. Select an installed OpenVPN app to import it."
            try {
                val uri = FileProvider.getUriForFile(this@OpenVpnCatalogActivity, "$packageName.openvpnfiles", file)
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/x-openvpn-profile")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                }
                try {
                    startActivity(Intent.createChooser(viewIntent, "Import profile into OpenVPN"))
                } catch (_: ActivityNotFoundException) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/x-openvpn-profile"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(sendIntent, "Import profile into OpenVPN"))
                }
            } catch (e: Exception) {
                status.text = "Profile saved, but no compatible importer opened: ${e.message ?: "install an OpenVPN client and try again"}"
            }
        }
    }

    private fun cleanHtml(s: String): String = decodeHtml(s.replace(Regex("(?is)<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ").trim()

    private fun decodeHtml(s: String): String = s
        .replace("&amp;", "&", true).replace("&quot;", "\"", true)
        .replace("&#39;", "'", true).replace("&#x27;", "'", true)
        .replace("&#x2F;", "/", true).replace("&#47;", "/", true)
        .replace("&nbsp;", " ", true)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
