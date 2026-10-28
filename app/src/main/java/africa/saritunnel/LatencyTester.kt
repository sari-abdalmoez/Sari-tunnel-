package africa.saritunnel

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Measures TCP connect time to each unique host:port. Run it only while no VPN is active, otherwise
 * the numbers include the tunnel. A fast answer means the port is open, not that VLESS works.
 */
object LatencyTester {
    data class Result(val reachable: Boolean, val pingMs: Int)

    /** Tests every unique endpoint once; [onResult] is called on the main thread per endpoint. */
    suspend fun test(
        servers: List<VlessServer>,
        concurrency: Int = 6,
        timeoutMs: Int = 4_000,
        onResult: (endpointKey: String, result: Result) -> Unit
    ): Map<String, Result> = coroutineScope {
        val unique = servers.associateBy { it.endpointKey }.values.toList()
        val gate = Semaphore(concurrency)
        val results = ConcurrentHashMap<String, Result>()
        unique.map { server ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    ensureActive()
                    val result = measure(server.host, server.port, timeoutMs)
                    results[server.endpointKey] = result
                    withContext(Dispatchers.Main) { onResult(server.endpointKey, result) }
                }
            }
        }.awaitAll()
        results
    }

    private fun measure(host: String, port: Int, timeoutMs: Int): Result {
        val address = try { InetAddress.getByName(host) } catch (_: Exception) { return Result(false, -1) }
        val first = connectOnce(address, port, timeoutMs)
        if (first < 0) return Result(false, -1)
        val second = connectOnce(address, port, timeoutMs)
        val best = if (second in 0 until first) second else first
        return Result(true, best.coerceAtLeast(1))
    }

    /** Returns milliseconds or -1 when the connection fails or times out. */
    private fun connectOnce(address: InetAddress, port: Int, timeoutMs: Int): Int {
        val socket = Socket()
        return try {
            val start = SystemClock.elapsedRealtimeNanos()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L).toInt()
        } catch (_: Exception) {
            -1
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }
}
