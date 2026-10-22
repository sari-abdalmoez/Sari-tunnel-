package africa.saritunnel

import android.content.Context
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class Server(
    val id: String,
    val name: String,
    val country: String,
    val configText: String,
    val endpointHost: String,
    val latencyMs: Long = Long.MAX_VALUE
)

object ServerDiscovery {
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_SERVERS = 50
    private const val MAX_CONFIG_CHARS = 8192
    private const val RESOLVE_TIMEOUT_MS = 3_000L

    private val failures = ConcurrentHashMap<String, Int>()

    /** Collects candidates from the user's own config and their own HTTPS feed. */
    suspend fun candidates(ctx: Context): List<Server> {
        val out = mutableListOf<Server>()
        ConfigStore.customConfig(ctx)?.let { text ->
            makeServer("custom", "Custom config", "", text)?.let { out.add(it) }
        }
        val feed = ConfigStore.feedUrl(ctx)
        if (feed.isNotBlank()) out.addAll(loadFeed(ctx, feed))
        return out.distinctBy { it.id }.take(MAX_SERVERS)
    }

    /** Ranks by DNS resolution latency, then by recent failures. */
    suspend fun rank(list: List<Server>): List<Server> = coroutineScope {
        val gate = Semaphore(4)
        list.map { s ->
            async(Dispatchers.IO) {
                gate.withPermit { s.copy(latencyMs = resolveMs(s.endpointHost)) }
            }
        }.awaitAll()
            .filter { it.latencyMs != Long.MAX_VALUE }
            .sortedWith(compareBy<Server>({ failures[it.id] ?: 0 }, { it.latencyMs }))
    }

    fun recordFailure(s: Server) {
        failures.merge(s.id, 1) { a, b -> a + b }
    }

    fun recordSuccess(s: Server) {
        failures.remove(s.id)
    }

    private suspend fun loadFeed(ctx: Context, url: String): List<Server> = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            Diag.w("Feed rejected: HTTPS is required")
            return@withContext emptyList<Server>()
        }
        val cache = ConfigStore.feedCache(ctx)
        if (cache != null && cache.first == url &&
            System.currentTimeMillis() - cache.second < CACHE_TTL_MS
        ) {
            Diag.i("Using cached server feed")
            return@withContext parseFeed(cache.third)
        }
        val body = fetch(url)
        if (body != null) {
            ConfigStore.putFeedCache(ctx, url, body)
            Diag.i("Server feed loaded")
            parseFeed(body)
        } else {
            Diag.w("Server feed fetch failed")
            if (cache != null && cache.first == url) parseFeed(cache.third) else emptyList()
        }
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200) {
                null
            } else {
                val out = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (out.size() <= MAX_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                if (out.size() > MAX_BYTES) null else String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseFeed(json: String): List<Server> = try {
        val arr = JSONArray(json)
        val count = minOf(arr.length(), MAX_SERVERS)
        (0 until count).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            makeServer(
                "feed-$i",
                o.optString("name", "Server ${i + 1}"),
                o.optString("country", ""),
                o.optString("config", "")
            )
        }
    } catch (e: Exception) {
        Diag.w("Server feed is not valid JSON")
        emptyList()
    }

    /** Validates a WireGuard config and extracts the endpoint host. Returns null if invalid. */
    private fun makeServer(id: String, name: String, country: String, text: String): Server? {
        if (text.isBlank() || text.length > MAX_CONFIG_CHARS) return null
        return try {
            Config.parse(BufferedReader(StringReader(text)))
            val host = endpointHost(text) ?: return null
            Server(id, name.take(40), country.take(8), text, host)
        } catch (e: Exception) {
            Diag.w("Skipped a server with an invalid configuration")
            null
        }
    }

    private fun endpointHost(text: String): String? {
        val raw = Regex("(?im)^\\s*Endpoint\\s*=\\s*(\\S+)\\s*$").find(text)
            ?.groupValues?.get(1) ?: return null
        val idx = raw.lastIndexOf(':')
        if (idx <= 0) return null
        val host = raw.substring(0, idx).trim('[', ']')
        return host.takeIf { it.isNotEmpty() && it.length < 256 }
    }

    /** Thread-based timeout, because InetAddress.getByName has no timeout of its own. */
    private fun resolveMs(host: String): Long {
        var ms = Long.MAX_VALUE
        val t = Thread {
            try {
                val start = System.nanoTime()
                InetAddress.getByName(host)
                ms = (System.nanoTime() - start) / 1_000_000
            } catch (e: Exception) {
                // unresolvable host: keep Long.MAX_VALUE
            }
        }
        t.isDaemon = true
        t.start()
        t.join(RESOLVE_TIMEOUT_MS)
        return ms
    }
}
