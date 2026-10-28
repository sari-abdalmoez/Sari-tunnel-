package africa.saritunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Xray TUN service for VLESS. Android allows only one active VPN at a time.
 *
 * The state is reported as CONNECTED only after an HTTPS request made through the tunnel succeeds.
 */
class XrayVpnService : VpnService() {
    companion object {
        const val ACTION_START = "africa.saritunnel.xray.START"
        const val ACTION_STOP = "africa.saritunnel.xray.STOP"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_URI = "vless_uri"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SERVER_NAME = "server_name"
        private const val CHANNEL = "sari_xray_vpn"
        private const val NOTIFICATION_ID = 8812
        private val PROBE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationLock = Mutex()

    /** Bumped by every start/stop request so an older attempt notices it was superseded. */
    private val generation = AtomicInteger(0)
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var coreStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            generation.incrementAndGet()
            TunnelState.update(TunnelState.Phase.DISCONNECTING, message = "Disconnecting…")
            scope.launch {
                operationLock.withLock { shutdownCore("Disconnected") }
                TunnelState.update(TunnelState.Phase.IDLE, serverId = null, message = "Disconnected")
                TunnelState.log("Disconnected.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val uri = intent?.getStringExtra(EXTRA_URI)?.takeIf { it.isNotBlank() }
        val rawConfig = intent?.getStringExtra(EXTRA_CONFIG)?.takeIf { it.isNotBlank() }
        if (uri == null && rawConfig == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val serverId = intent?.getStringExtra(EXTRA_SERVER_ID)
        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty().ifBlank { "server" }

        val myGeneration = generation.incrementAndGet()
        startForeground(NOTIFICATION_ID, notification("Preparing secure VLESS tunnel…"))
        setStatus("Starting Xray engine")
        TunnelState.update(TunnelState.Phase.CONNECTING, serverId, "Connecting to $serverName…")
        TunnelState.log("Connecting to $serverName.")

        scope.launch {
            operationLock.withLock {
                if (myGeneration != generation.get()) return@withLock
                try {
                    // Switching servers: release the previous tunnel first.
                    if (coreStarted || tun != null) shutdownCore()

                    val config = if (uri != null) {
                        XrayConfigBuilder.fromVlessUri(uri, resolveServerAddress(uri))
                    } else rawConfig!!
                    if (myGeneration != generation.get()) return@withLock

                    tun = Builder()
                        .setSession("SariTunnel · VLESS")
                        .setMtu(1280)
                        .addAddress("10.111.0.1", 32)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("1.1.1.1")
                        .addAddress("fd11:2233:4455::1", 128)
                        .addRoute("::", 0)
                        .establish() ?: throw IllegalStateException("Android did not create the VPN interface.")
                    if (myGeneration != generation.get()) {
                        shutdownCore()
                        return@withLock
                    }

                    XrayBridge.start(this@XrayVpnService, config, tun!!.fd, filesDir.absolutePath)
                    coreStarted = true
                    setStatus("Xray core started. Verifying traffic.")
                    TunnelState.update(TunnelState.Phase.VERIFYING, serverId, "Verifying internet access…")
                    TunnelState.log("Xray core started; verifying HTTPS traffic through the tunnel.")
                    updateNotification("Verifying connection…")

                    val working = verifyTraffic(myGeneration)
                    if (myGeneration != generation.get()) return@withLock
                    if (working) {
                        VlessSubscriptionManager(applicationContext).recordConnection(serverId, true)
                        setStatus("Connected · traffic verified")
                        TunnelState.update(
                            TunnelState.Phase.CONNECTED, serverId, "Connected to $serverName",
                            connectedAtMs = System.currentTimeMillis()
                        )
                        TunnelState.log("Traffic verified. Connected to $serverName.")
                        updateNotification("Connected to $serverName")
                    } else {
                        VlessSubscriptionManager(applicationContext).recordConnection(serverId, false)
                        val failure = "The server accepted the tunnel but no internet traffic passed."
                        shutdownCore("Failed: $failure")
                        TunnelState.update(TunnelState.Phase.FAILED, serverId, failure)
                        TunnelState.log("$serverName failed: $failure")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                } catch (e: Exception) {
                    if (myGeneration != generation.get()) {
                        shutdownCore()
                        return@withLock
                    }
                    val failure = e.message ?: e.javaClass.simpleName
                    VlessSubscriptionManager(applicationContext).recordConnection(serverId, false)
                    shutdownCore("Failed: $failure")
                    TunnelState.update(TunnelState.Phase.FAILED, serverId, failure)
                    TunnelState.log("$serverName failed: $failure")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    /** Resolves the server host before the TUN exists; IP literals are used as they are. */
    private fun resolveServerAddress(vlessUri: String): String? {
        val host = Uri.parse(vlessUri).host?.takeIf { it.isNotBlank() } ?: return null
        if (host.contains(':') || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return null
        val all = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw IllegalStateException("Could not resolve the server address ($host).")
        }
        return (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
    }

    /** An HTTPS request from this process goes through the TUN, so success proves the tunnel carries data. */
    private fun verifyTraffic(expected: Int): Boolean {
        repeat(2) { attempt ->
            for (url in PROBE_URLS) {
                if (expected != generation.get()) return false
                if (probe(url)) return true
            }
            if (attempt == 0) Thread.sleep(700)
        }
        return false
    }

    private fun probe(url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("SariTunnel · VLESS")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Disconnect", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "SariTunnel VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun setStatus(message: String) {
        getSharedPreferences("xray_state", MODE_PRIVATE).edit().putString("status", message).apply()
    }

    private fun shutdownCore(finalStatus: String? = null) {
        runCatching { if (coreStarted) XrayBridge.stop() }
        coreStarted = false
        runCatching { tun?.close() }
        tun = null
        if (finalStatus != null) setStatus(finalStatus)
    }

    /** Another VPN took over or the user revoked the permission in system settings. */
    override fun onRevoke() {
        generation.incrementAndGet()
        scope.launch {
            operationLock.withLock { shutdownCore("Disconnected") }
            TunnelState.update(TunnelState.Phase.IDLE, serverId = null, message = "VPN was revoked by Android or replaced by another VPN.")
            TunnelState.log("VPN revoked by the system or replaced by another VPN.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        // Android can destroy the service independently of the app UI. Finish native cleanup
        // before cancelling the scope so the Go core and the TUN descriptor are not leaked.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            operationLock.withLock {
                val hadActiveTunnel = coreStarted || tun != null
                shutdownCore(if (hadActiveTunnel) "Disconnected" else null)
                if (hadActiveTunnel || TunnelState.isActive()) {
                    TunnelState.update(TunnelState.Phase.IDLE, serverId = null, message = "Disconnected")
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
