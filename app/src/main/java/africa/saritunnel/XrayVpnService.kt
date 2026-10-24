package africa.saritunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import java.util.concurrent.atomic.AtomicBoolean

/** Xray TUN service for VLESS. Android allows only one active VPN at a time. */
class XrayVpnService : VpnService() {
    companion object {
        const val ACTION_START = "africa.saritunnel.xray.START"
        const val ACTION_STOP = "africa.saritunnel.xray.STOP"
        const val EXTRA_CONFIG = "xray_config"
        private const val CHANNEL = "sari_xray_vpn"
        private const val NOTIFICATION_ID = 8812
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationLock = Mutex()
    private val stopRequested = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var coreStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested.set(true)
            scope.launch {
                operationLock.withLock { shutdownCore("Disconnected") }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val config = intent?.getStringExtra(EXTRA_CONFIG)?.takeIf { it.isNotBlank() }
        if (config == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (coreStarted) return START_STICKY

        stopRequested.set(false)
        startForeground(NOTIFICATION_ID, notification("Preparing secure VLESS tunnel…", true))
        setStatus("Starting Xray engine")
        scope.launch {
            operationLock.withLock {
                try {
                    if (stopRequested.get()) return@withLock
                    tun?.close()
                    tun = Builder()
                        .setSession("SariTunnel · VLESS")
                        .setMtu(1500)
                        .addAddress("10.111.0.1", 32)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("1.1.1.1")
                        .addAddress("fd11:2233:4455::1", 128)
                        .addRoute("::", 0)
                        .establish() ?: throw IllegalStateException("Android did not create the VPN interface.")
                    if (stopRequested.get()) {
                        shutdownCore()
                        return@withLock
                    }
                    XrayBridge.start(this@XrayVpnService, config, tun!!.fd, filesDir.absolutePath)
                    coreStarted = true
                    setStatus("Xray core started. Verify by loading a website.")
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("Xray started · VLESS tunnel active", true)
                    )
                } catch (e: Exception) {
                    val failure = "Failed: ${e.message ?: e.javaClass.simpleName}"
                    shutdownCore(failure)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun notification(message: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VlessServerCatalogActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Sari VPN · VLESS")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .addAction(0, "Disconnect", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Sari VPN tunnel", NotificationManager.IMPORTANCE_LOW)
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

    override fun onDestroy() {
        stopRequested.set(true)
        // Android can destroy the service independently of the app UI. Finish native cleanup
        // before cancelling the scope so the Go core and the TUN descriptor are not leaked.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            operationLock.withLock {
                val hadActiveTunnel = coreStarted || tun != null
                shutdownCore(if (hadActiveTunnel) "Disconnected" else null)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
