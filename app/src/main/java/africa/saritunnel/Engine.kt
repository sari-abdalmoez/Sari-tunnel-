package africa.saritunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

enum class TunnelState {
    IDLE, CHECKING_NETWORK, DISCOVERING, TESTING, CONNECTING, VERIFYING, CONNECTED,
    RETRYING, RECONNECTING, STOPPING, ERROR
}

data class Stats(
    val rxTotal: Long = 0L,
    val txTotal: Long = 0L,
    val rxSpeed: Long = 0L,
    val txSpeed: Long = 0L
)

/** Connection state machine. Reports CONNECTED only after the tunnel passes verification. */
object Engine {
    private const val TUNNEL_NAME = "SariTunnel"
    private const val MAX_ROUNDS = 3
    private const val VERIFY_URL = "https://www.gstatic.com/generate_204"
    const val NO_SERVER_MSG =
        "No supported server is available. Add a legitimate WireGuard config or HTTPS feed in Advanced settings."

    val state = MutableStateFlow(TunnelState.IDLE)
    val message = MutableStateFlow("Ready")
    val server = MutableStateFlow<Server?>(null)
    val connectedAt = MutableStateFlow(0L)
    val stats = MutableStateFlow(Stats())

    private lateinit var app: Context
    private lateinit var backend: GoBackend
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Volatile
    private var wantConnected = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Diag.i("Tunnel event: ${newState.name}")
        }
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        backend = GoBackend(app)
    }

    fun start() {
        if (wantConnected) return
        wantConnected = true
        loop = scope.launch { connectLoop() }
    }

    fun stop() {
        wantConnected = false
        loop?.cancel()
        loop = null
        scope.launch {
            setState(TunnelState.STOPPING, "Disconnecting…")
            bringDown()
            server.value = null
            connectedAt.value = 0L
            stats.value = Stats()
            setState(TunnelState.IDLE, "Disconnected")
        }
    }

    private suspend fun connectLoop() {
        var failures = 0
        var backoff = 2_000L
        var everConnected = false

        while (wantConnected) {
            try {
                val ranked = discoverAndRank()
                for (candidate in ranked) {
                    if (!wantConnected) return
                    try {
                        connectTo(candidate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        bringDown()
                        ServerDiscovery.recordFailure(candidate)
                        Diag.w("${candidate.name}: ${describe(e)}")
                        continue
                    }
                    everConnected = true
                    failures = 0
                    backoff = 2_000L
                    monitor() // returns only on stop; throws when the tunnel drops
                    return
                }
                throw IllegalStateException("All candidate servers failed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!wantConnected) return
                bringDown()
                connectedAt.value = 0L
                failures++
                if (failures >= MAX_ROUNDS) {
                    wantConnected = false
                    setState(TunnelState.ERROR, describe(e))
                    return
                }
                val next = if (everConnected) TunnelState.RECONNECTING else TunnelState.RETRYING
                setState(next, "Retrying in ${backoff / 1000} s…")
                delay(backoff)
                backoff = min(backoff * 2, 60_000L)
            }
        }
    }

    private suspend fun discoverAndRank(): List<Server> {
        setState(TunnelState.CHECKING_NETWORK, "Checking network…")
        if (!hasInternet()) throw IllegalStateException("No internet connection")

        setState(TunnelState.DISCOVERING, "Finding servers…")
        val candidates = ServerDiscovery.candidates(app)
        if (candidates.isEmpty()) throw IllegalStateException(NO_SERVER_MSG)

        setState(TunnelState.TESTING, "Testing ${candidates.size} server(s)…")
        val ranked = ServerDiscovery.rank(candidates)
        if (ranked.isEmpty()) throw IllegalStateException("No server responded to name resolution")
        return ranked
    }

    private suspend fun connectTo(s: Server) {
        server.value = s
        setState(TunnelState.CONNECTING, "Connecting to ${s.name}…")
        val config = Config.parse(BufferedReader(StringReader(s.configText)))
        backend.setState(tunnel, Tunnel.State.UP, config)

        setState(TunnelState.VERIFYING, "Verifying tunnel…")
        if (!awaitVpnNetwork()) throw IllegalStateException("VPN interface did not come up")
        if (!testThroughTunnel()) throw IllegalStateException("Test traffic did not pass through tunnel")

        ServerDiscovery.recordSuccess(s)
        connectedAt.value = System.currentTimeMillis()
        setState(TunnelState.CONNECTED, "Connected to ${s.name}")
    }

    private suspend fun monitor() {
        val first = backend.getStatistics(tunnel)
        var lastRx = first.totalRx()
        var lastTx = first.totalTx()
        var lastT = System.currentTimeMillis()
        var ticks = 0

        while (wantConnected) {
            delay(2_000)
            if (backend.getState(tunnel) != Tunnel.State.UP) {
                throw IllegalStateException("Tunnel went down")
            }
            val s = backend.getStatistics(tunnel)
            val now = System.currentTimeMillis()
            val secs = max(1L, now - lastT) / 1000.0
            val rx = s.totalRx()
            val tx = s.totalTx()
            stats.value = Stats(
                rxTotal = rx,
                txTotal = tx,
                rxSpeed = ((rx - lastRx) / secs).toLong().coerceAtLeast(0L),
                txSpeed = ((tx - lastTx) / secs).toLong().coerceAtLeast(0L)
            )
            lastRx = rx
            lastTx = tx
            lastT = now

            ticks++
            if (ticks % 15 == 0 && !testThroughTunnel()) {
                throw IllegalStateException("Health check failed")
            }
        }
    }

    private fun bringDown() {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            Diag.w("Teardown: ${e.javaClass.simpleName}")
        }
    }

    private fun cm(): ConnectivityManager = app.getSystemService(ConnectivityManager::class.java)

    private fun vpnNetwork(): Network? {
        val c = cm()
        return c.allNetworks.firstOrNull { n ->
            c.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun hasInternet(): Boolean {
        val c = cm()
        val net = c.activeNetwork ?: return false
        val caps = c.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun awaitVpnNetwork(): Boolean {
        repeat(20) {
            if (vpnNetwork() != null) return true
            delay(500)
        }
        return vpnNetwork() != null
    }

    /** Binds the request to the VPN network so the traffic must go through the tunnel. */
    private fun testThroughTunnel(): Boolean {
        val net = vpnNetwork() ?: return false
        return try {
            val conn = net.openConnection(URL(VERIFY_URL)) as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code == 204
        } catch (e: Exception) {
            false
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is BadConfigException -> "Invalid server configuration"
        is IllegalStateException -> e.message ?: "Connection failed"
        else -> "Connection failed (${e.javaClass.simpleName})"
    }

    private fun setState(s: TunnelState, msg: String) {
        state.value = s
        message.value = msg
        Diag.i(msg)
    }
}
