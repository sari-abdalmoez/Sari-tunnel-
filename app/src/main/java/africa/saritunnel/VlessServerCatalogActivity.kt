package africa.saritunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Imports complete VLESS share URIs from the user's published Blogger page. */
class VlessServerCatalogActivity : AppCompatActivity() {
    data class Entry(val id: String, val label: String, val country: String, val ip: String?, val uri: String?)

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refresh: Button
    private var entries: List<Entry> = emptyList()
    private var pendingConfig: String? = null

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = pendingConfig
        pendingConfig = null
        if (result.resultCode == RESULT_OK && !config.isNullOrBlank()) startXray(config)
        else if (!config.isNullOrBlank()) setStatus("Android VPN permission was not granted.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        val savedStatus = getSharedPreferences("xray_state", MODE_PRIVATE).getString("status", null)
        if (!savedStatus.isNullOrBlank()) status.text = "VLESS status: $savedStatus"
        refreshFeed()
    }

    override fun onResume() {
        super.onResume()
        val state = getSharedPreferences("xray_state", MODE_PRIVATE).getString("status", null)
        if (!state.isNullOrBlank()) status.text = "VLESS status: $state"
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_background))
        }
        root.addView(TextView(this).apply {
            text = "VLESS / Xray servers"
            textSize = 24f
            setTextColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Source: connection7876.blogspot.com"
            textSize = 12f
            setPadding(0, dp(3), 0, dp(8))
            setTextColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_muted))
        })
        status = TextView(this).apply {
            text = "Reading the published server page…"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_text))
            setPadding(0, dp(5), 0, dp(8))
        }
        root.addView(status)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        refresh = Button(this).apply { text = "Refresh list"; setOnClickListener { refreshFeed() } }
        actions.addView(refresh, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(Button(this).apply {
            text = "Disconnect VLESS"
            setOnClickListener {
                try {
                    startService(Intent(this@VlessServerCatalogActivity, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
                    setStatus("VLESS disconnect requested. If another VPN is active, disconnect it in its own app.")
                } catch (e: Exception) { setStatus("Could not request disconnect: ${e.message ?: "unknown error"}") }
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        root.addView(actions)

        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(TextView(this).apply {
            text = "Android can run only one VPN at a time. Disconnect OpenVPN or WireGuard before starting VLESS. IP-only entries are shown for reference and cannot be connected without a complete protocol configuration."
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_muted))
            setPadding(0, dp(8), 0, dp(10))
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, dp(44)))
        return root
    }

    private fun refreshFeed() {
        progress.visibility = View.VISIBLE
        refresh.isEnabled = false
        setStatus("Fetching current VLESS links from the Blogger feed…")
        lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { fetchEntries() }
                entries = found
                renderEntries()
                val profiles = found.count { it.uri != null }
                val ipOnly = found.count { it.uri == null }
                setStatus("Loaded ${found.size} entries: $profiles complete VLESS link(s), $ipOnly IP-only item(s).")
            } catch (e: Exception) {
                entries = emptyList()
                renderEntries()
                setStatus("Could not load the Blogger feed: ${e.message ?: e.javaClass.simpleName}. Check the connection and retry.")
            } finally {
                progress.visibility = View.GONE
                refresh.isEnabled = true
            }
        }
    }

    private fun fetchEntries(): List<Entry> {
        val connection = (URL(BLOG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        val html = try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Blogger returned HTTP $code")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > MAX_FEED_BYTES) throw IOException("The server page is too large")
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } finally { connection.disconnect() }
        return parseEntries(html)
    }

    internal fun parseEntries(html: String): List<Entry> {
        val doc = Jsoup.parse(html, BLOG_URL)
        val decodedHtml = html.replace("&amp;", "&", ignoreCase = true)
        val result = LinkedHashMap<String, Entry>()
        val linkedCountries = HashMap<String, String>()
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").replace("&amp;", "&", ignoreCase = true).trim()
            if (!href.startsWith("vless://", ignoreCase = true)) continue
            val area = anchor.closest("tr") ?: anchor.parent()
            val country = countryInContext(area?.text().orEmpty())
            if (country != null) linkedCountries[href.trimEnd(',', ';', ')', ']', '.')] = country
        }

        val matches = VLESS_REGEX.findAll(decodedHtml)
        for (match in matches) {
            val share = match.value.trim().trimEnd(',', ';', ')', ']', '.')
            if (share.contains("...") || share.contains('…')) continue
            val uri = runCatching { Uri.parse(share) }.getOrNull() ?: continue
            val host = uri.host?.takeIf { it.isNotBlank() } ?: continue
            val port = uri.port
            val uuid = uri.userInfo.orEmpty().substringBefore(':')
            if (!share.startsWith("vless://", true) || uuid.isBlank() || port !in 1..65535) continue
            // Context uses the visible page text before this URI, which also handles raw text links.
            val contextStart = (match.range.first - 1600).coerceAtLeast(0)
            val contextText = Jsoup.parse(decodedHtml.substring(contextStart, match.range.first)).text().takeLast(260)
            val rowCountry = countryInContext(contextText) ?: linkedCountries[share]
            val label = uri.fragment?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() }
                ?: rowCountry ?: "${host}:$port"
            result.putIfAbsent(share, Entry("vless-${result.size}", label, rowCountry ?: "VLESS", host, share))
        }

        // Parse country/IP rows separately. An IP without protocol, port and credentials is not a tunnel.
        for (node in doc.select("tr, p, li, pre, .post-body div, .post-body span")) {
            val text = node.text().trim()
            if (text.isBlank() || "vless://" in text.lowercase(Locale.ROOT) || node.html().contains("vless://", true)) continue
            val ip = IPV4_REGEX.find(text)?.value ?: continue
            val country = countryInContext(text) ?: continue
            val key = "ip:$country:$ip"
            result.putIfAbsent(key, Entry(key, "$country · $ip", country, ip, null))
        }
        val visibleText = doc.body()?.wholeText().orEmpty().replace(Regex("[\\t\\r]+"), " ")
        val pair = COUNTRY_IP_REGEX.findAll(visibleText)
        for (m in pair) {
            val country = canonicalCountry(m.groupValues[1]) ?: continue
            val ip = m.groupValues[2]
            val key = "ip:$country:$ip"
            result.putIfAbsent(key, Entry(key, "$country · $ip", country, ip, null))
        }
        return result.values.toList().take(MAX_ENTRIES)
    }

    private fun renderEntries() {
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No complete profiles were found. Ensure the Blogger post publishes full vless:// links; an IP address on its own is not a VPN profile."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_muted))
                setPadding(dp(10), dp(18), dp(10), dp(18))
            })
            return
        }
        entries.forEach { entry ->
            val card = MaterialCardView(this).apply {
                radius = dp(15).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_surface_alt))
                setCardBackgroundColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_surface))
                setContentPadding(dp(13), dp(12), dp(13), dp(12))
            }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(this).apply {
                text = entry.label
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_text))
            })
            column.addView(TextView(this).apply {
                text = if (entry.uri != null) "VLESS · ${entry.ip ?: "server"} · Xray engine" else "IP only · missing protocol, port and authentication/config"
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
                setTextColor(ContextCompat.getColor(this@VlessServerCatalogActivity, R.color.sari_muted))
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (entry.uri != null) {
                buttons.addView(Button(this@VlessServerCatalogActivity).apply {
                    text = "Connect VLESS"
                    setOnClickListener { requestConnect(entry.uri) }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            buttons.addView(Button(this@VlessServerCatalogActivity).apply {
                text = if (entry.uri != null) "Copy link" else "Copy IP"
                setOnClickListener { copy(entry.uri ?: entry.ip.orEmpty()) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
            column.addView(buttons)
            card.addView(column)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    private fun requestConnect(share: String) {
        val cfg = try { XrayConfigBuilder.fromVlessUri(share) } catch (e: Exception) {
            setStatus("This VLESS link cannot be used: ${e.message ?: "invalid profile"}")
            return
        }
        pendingConfig = cfg
        val consent = VpnService.prepare(this)
        if (consent == null) {
            pendingConfig = null
            startXray(cfg)
        } else consentLauncher.launch(consent)
    }

    private fun startXray(config: String) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_START)
                    .putExtra(XrayVpnService.EXTRA_CONFIG, config)
            )
            setStatus("Starting Xray. Disconnect any currently active VPN first if Android rejects the request.")
        } catch (e: Exception) {
            setStatus("Could not start VLESS engine: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Sari VPN server", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(message: String) {
        status.text = message
        getSharedPreferences("xray_state", MODE_PRIVATE).edit().putString("ui_status", message).apply()
    }

    private fun countryInContext(text: String): String? = COUNTRY_REGEX.find(text)?.groupValues?.getOrNull(1)?.let(::canonicalCountry)

    private fun canonicalCountry(country: String): String? = COUNTRIES.firstOrNull { it.equals(country, true) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val BLOG_URL = "https://connection7876.blogspot.com/?m=1"
        private const val MAX_FEED_BYTES = 2 * 1024 * 1024
        private const val MAX_ENTRIES = 150
        private val VLESS_REGEX = Regex("(?i)vless://[^\\s\\\"'<>]+")
        private val IPV4_REGEX = Regex("\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b")
        private val COUNTRIES = listOf(
            "Australia", "Austria", "Belgium", "Brazil", "Canada", "China", "Denmark", "Finland", "France",
            "Germany", "Hong Kong", "India", "Indonesia", "Ireland", "Israel", "Italy", "Japan", "Mexico",
            "Netherlands", "New Zealand", "Norway", "Poland", "Portugal", "Romania", "Russia", "Singapore",
            "South Africa", "South Korea", "Spain", "Sweden", "Switzerland", "Taiwan", "Turkey", "Ukraine",
            "United Arab Emirates", "United Kingdom", "United States", "United States of America", "Vietnam"
        )
        private val COUNTRY_REGEX = Regex(
            "(?i)\\b(${COUNTRIES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})\\b"
        )
        private val COUNTRY_IP_REGEX = Regex(
            "(?i)\\b(${COUNTRIES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})\\b[\\s:–—-]{0,40}($IPV4_REGEX)"
        )
    }
}
