package africa.saritunnel

import africa.saritunnel.databinding.ActivityMainBinding
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var vpnClient: OpenVpnClient

    private var servers: List<OpenVpnServer> = emptyList()
    private var currentServer: OpenVpnServer? = null
    private var returningFromAdvancedSettings = false
    private var selectedProtocol = "OPENVPN"
    private var autoConnectAfterRefresh = false
    private var torLaunchRequested = false
    private var vlessLaunchRequested = false
    private var currentIndex = -1
    private var generation = 0
    private var attemptsSinceSelection = 0
    private var isAttemptActive = false
    private var isConnected = false
    private var isVerifying = false
    private var manualStop = false
    private var retryQueued = false
    private var startedAtElapsed = 0L
    private var connectedAtWallClock = 0L
    private var connectionWatchdog: Job? = null
    private var retryJob: Job? = null

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (::vpnClient.isInitialized) vpnClient.onPermissionResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        vpnClient = OpenVpnClient(
            activity = this,
            launchConsent = { intent -> consentLauncher.launch(intent) },
            onStatus = ::handleVpnStatus,
            onStarted = ::handleVpnStarted,
            onError = ::handleClientError
        )

        b.openVpnButton.setOnClickListener { selectProtocol("OPENVPN") }
        b.vlessServersButton.setOnClickListener { selectProtocol("VLESS") }
        b.torButton.setOnClickListener { selectProtocol("TOR") }
        b.startButton.setOnClickListener {
            if (isAttemptActive || isConnected) disconnectByUser()
            else when (selectedProtocol) {
                "OPENVPN" -> { autoConnectAfterRefresh = true; refreshServers(force = true) }
                "VLESS" -> { if (vlessLaunchRequested) stopVlessFromMain() else { openProtocol(VlessServerCatalogActivity::class.java); vlessLaunchRequested = true; updateStartButton() } }
                "TOR" -> launchOrManageTor()
            }
        }
        b.refreshServersButton.setOnClickListener {
            if (isAttemptActive || isConnected) {
                toast("Disconnect before refreshing the server list.")
            } else refreshServers(force = true)
        }
        b.advancedButton.setOnClickListener {
            returningFromAdvancedSettings = true
            startActivity(Intent(this, AdvancedActivity::class.java))
        }
        b.diagToggle.setOnClickListener {
            val show = b.diagPanel.visibility != View.VISIBLE
            b.diagPanel.visibility = if (show) View.VISIBLE else View.GONE
            b.diagToggle.text = if (show) "Hide connection log" else "Show connection log"
        }

        lifecycleScope.launch {
            while (isActive) {
                if (isConnected && connectedAtWallClock > 0L) {
                    val seconds = ((System.currentTimeMillis() - connectedAtWallClock) / 1000).coerceAtLeast(0)
                    b.elapsedText.text = String.format(
                        Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60
                    )
                } else if (!isAttemptActive) {
                    b.elapsedText.text = "00:00:00"
                }
                delay(1_000)
            }
        }

        updateProtocolButtons()
        updateStartButton()
        appendLog("Sari VPN ready. Loading OpenVPN profiles.")
        refreshServers(force = false)
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized && returningFromAdvancedSettings) {
            returningFromAdvancedSettings = false
            if (!isAttemptActive && !isConnected) refreshServers(force = true)
        }
    }

    private fun openProtocol(destination: Class<*>) {
        if (isAttemptActive || isConnected) {
            AlertDialog.Builder(this)
                .setTitle("Switch VPN engine?")
                .setMessage("OpenVPN is active or still connecting. Disconnect it before switching to VLESS or Tor VPN; Android only supports one active VPN at a time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect and continue") { _, _ ->
                    disconnectByUser()
                    startActivity(Intent(this, destination))
                }
                .show()
        } else startActivity(Intent(this, destination))
    }

    private fun selectProtocol(protocol: String) {
        if (isAttemptActive || isConnected) { toast("Stop the current VPN before changing protocol."); return }
        selectedProtocol = protocol
        updateProtocolButtons(); updateStartButton()
        b.statusMessage.text = when (protocol) {
            "OPENVPN" -> "OpenVPN selected · auto-pick the lowest reported-ping complete profile."
            "VLESS" -> "VLESS / Xray selected · load your published server links."
            else -> "Tor VPN selected · uses the official Tor VPN app for Android."
        }
    }
    private fun updateProtocolButtons() {
        val selected = ContextCompat.getColor(this, R.color.sari_blue)
        val idle = ContextCompat.getColor(this, R.color.sari_surface_alt)
        listOf(b.openVpnButton to "OPENVPN", b.vlessServersButton to "VLESS", b.torButton to "TOR").forEach { (button, protocol) ->
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selectedProtocol == protocol) selected else idle)
            button.setTextColor(ContextCompat.getColor(this, R.color.sari_text))
        }
    }
    private fun updateStartButton() {
        b.startButton.text = when {
            isAttemptActive || isConnected -> "STOP VPN"
            selectedProtocol == "TOR" && torLaunchRequested -> "MANAGE TOR VPN"
            selectedProtocol == "VLESS" && vlessLaunchRequested -> "STOP VLESS"
            else -> "CONNECT"
        }
    }
    private fun launchOrManageTor() {
        if (torLaunchRequested) {
            try { startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)); b.statusMessage.text = "VPN settings opened. Disconnect Tor there; Android does not let Sari directly stop another app's VPN." }
            catch (_: Exception) { toast("Open Android Settings → VPN to disconnect Tor VPN.") }
            torLaunchRequested = false; updateStartButton(); return
        }
        val torIntent = packageManager.getLaunchIntentForPackage("org.torproject.vpn")
        if (torIntent != null) {
            startActivity(torIntent); torLaunchRequested = true
            b.statusTitle.text = "TOR VPN"
            b.statusMessage.text = "Official Tor VPN opened. Start the connection in that app, then return here to manage it."
            updateStartButton()
        } else AlertDialog.Builder(this).setTitle("Install Tor VPN")
            .setMessage("Tor VPN is a separate official Android app. Install it from the Tor Project, then return and tap Connect.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Official download") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://download.torproject.org/tor-vpn-for-android/"))) }.show()
    }
    private fun stopVlessFromMain() {
        try {
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
            b.statusTitle.text = "VLESS STOP REQUESTED"; b.statusMessage.text = "A disconnect request was sent to the Xray service."
            appendLog("VLESS stop requested from the main screen.")
        } catch (e: Exception) { toast("Could not stop VLESS: ${e.message ?: e.javaClass.simpleName}") }
        finally { vlessLaunchRequested = false; updateStartButton() }
    }

    private fun refreshServers(force: Boolean) {
        b.serverProgress.visibility = View.VISIBLE
        b.refreshServersButton.isEnabled = false
        b.statusMessage.text = "Looking for OpenVPN profiles…"
        lifecycleScope.launch {
            try {
                val loaded = OpenVpnServerRepository.loadServers(this@MainActivity, forceRefresh = force)
                servers = loaded
                renderServerRows()
                val customFirst = loaded.firstOrNull()?.country == "Custom"
                b.statusTitle.text = "${loaded.size} SERVERS READY"
                b.statusMessage.text = if (loaded.isEmpty()) "No usable servers were found." else
                    "Sorted by the published ping. Failed connections move to the next candidate automatically."
                appendLog("Loaded ${loaded.size} profile candidate(s).${if (customFirst) " Custom profile is first." else ""}")
            } catch (e: Exception) {
                servers = emptyList()
                renderServerRows()
                b.statusTitle.text = "SERVER LIST UNAVAILABLE"
                b.statusMessage.text = e.message ?: "Could not load a server list. Check your network or backup feed."
                appendLog("Server discovery failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                b.serverProgress.visibility = View.GONE
                b.refreshServersButton.isEnabled = true
                if (autoConnectAfterRefresh) {
                    autoConnectAfterRefresh = false
                    val fastest = servers.indices
                        .filter { i -> !servers[i].profileUrl.isNullOrBlank() || !servers[i].inlineProfile.isNullOrBlank() }
                        .minByOrNull { i -> servers[i].reportedPingMs ?: Int.MAX_VALUE }
                    if (fastest != null && !isAttemptActive && !isConnected) {
                        appendLog("Auto-selecting the lowest reported-ping OpenVPN profile.")
                        startConnectionAtIndex(fastest, automatic = false)
                    } else if (!isAttemptActive && !isConnected) {
                        b.statusTitle.text = "NO CONNECTABLE SERVER"
                        b.statusMessage.text = "No complete .ovpn profile is available. IP addresses alone cannot connect."
                        updateStartButton()
                    }
                } else if (!isAttemptActive && !isConnected) updateStartButton()
            }
        }
    }

    private fun renderServerRows() {
        b.serverListContainer.removeAllViews()
        if (servers.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No server entries are available. Try Refresh or configure another HTTPS JSON feed in Advanced settings."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sari_muted))
                textSize = 13f
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            b.serverListContainer.addView(empty)
            return
        }

        servers.forEachIndexed { index, server ->
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.sari_surface))
                strokeWidth = dp(1)
                setStrokeColor(ContextCompat.getColor(this@MainActivity, R.color.sari_surface_alt))
                setContentPadding(dp(13), dp(12), dp(10), dp(12))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val title = TextView(this).apply {
                text = if (server.country == "Custom") server.name else "${server.country.ifBlank { "Unknown location" }}  ·  ${server.ip}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sari_text))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val connectable = !server.profileUrl.isNullOrBlank() || !server.inlineProfile.isNullOrBlank()
            val detail = TextView(this).apply {
                text = if (!connectable) "IP address only · add a valid .ovpn profile before connecting" else if (server.reportedPingMs != null) "OpenVPN  ·  reported ping ${server.reportedPingMs} ms" else "OpenVPN  ·  latency not supplied"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sari_muted))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            }
            textCol.addView(title)
            textCol.addView(detail)
            row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val connect = MaterialButton(this).apply {
                text = if (connectable) "CONNECT" else "CONFIG NEEDED"
                textSize = 10f
                minHeight = dp(38)
                isEnabled = connectable
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    manualStop = false
                    attemptsSinceSelection = 0
                    startConnectionAtIndex(index, automatic = false)
                }
            }
            row.addView(connect, LinearLayout.LayoutParams(dp(92), dp(40)).apply { marginStart = dp(8) })
            card.addView(row)
            b.serverListContainer.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
    }

    private fun startConnectionAtIndex(index: Int, automatic: Boolean) {
        if (index !in servers.indices) {
            finishFailed("No more candidate servers are available.")
            return
        }
        if (!automatic) attemptsSinceSelection = 0
        attemptsSinceSelection++
        if (attemptsSinceSelection > MAX_AUTO_ATTEMPTS) {
            finishFailed("Tried several servers without a successful connection.")
            return
        }

        generation++
        val token = generation
        retryQueued = false
        connectionWatchdog?.cancel()
        retryJob?.cancel()
        startedAtElapsed = 0L
        connectedAtWallClock = 0L
        isConnected = false
        isVerifying = false
        isAttemptActive = true
        currentIndex = index
        currentServer = servers[index]
        val server = servers[index]
        if (server.profileUrl.isNullOrBlank() && server.inlineProfile.isNullOrBlank()) {
            appendLog("${server.displayName} is an IP-only directory entry; a complete .ovpn profile is required.")
            if (automatic) {
                startConnectionAtIndex(index + 1, automatic = true)
            } else {
                finishFailed("This entry contains only an IP address. Add a complete .ovpn profile or use a valid VLESS/WireGuard configuration.")
            }
            return
        }

        vpnClient.disconnect()
        b.startButton.text = "STOP VPN"
        b.statusTitle.text = if (automatic) "SWITCHING SERVER" else "PREPARING VPN"
        b.statusMessage.text = "Loading ${server.displayName}…"
        b.serverText.text = server.displayName
        b.protocolText.text = "OpenVPN · waiting for secure tunnel handshake"
        b.downloadText.text = "↓ —"
        b.uploadText.text = "↑ —"
        b.usageText.text = "Public server statistics are not exposed by the external engine API."
        appendLog("${if (automatic) "Auto-switching to" else "Selected"} ${server.displayName} (${index + 1}/${servers.size}).")

        lifecycleScope.launch {
            try {
                val rawProfile = OpenVpnServerRepository.downloadProfile(server)
                val profile = OpenVpnConfigParser.prepare(rawProfile)
                if (token != generation || manualStop) return@launch
                b.statusMessage.text = "Starting OpenVPN on ${profile.host}:${profile.port}/${profile.protocol}…"
                b.protocolText.text = "OpenVPN · ${profile.protocol.uppercase(Locale.US)} · port ${profile.port}"
                appendLog("Profile loaded: ${profile.host}:${profile.port} (${profile.protocol}).")
                vpnClient.connect(profile.text)
            } catch (e: Exception) {
                if (token == generation && !manualStop) {
                    appendLog("${server.displayName} failed before connect: ${e.message ?: e.javaClass.simpleName}")
                    retryNext(index, token, e.message ?: "Could not download or parse profile")
                }
            }
        }
    }

    private fun handleVpnStarted() {
        if (!isAttemptActive || manualStop) return
        startedAtElapsed = SystemClock.elapsedRealtime()
        val token = generation
        val index = currentIndex
        b.statusTitle.text = "CONNECTING"
        b.statusMessage.text = "OpenVPN started. Waiting for the server handshake…"
        appendLog("OpenVPN engine accepted the profile; waiting for CONNECTED status.")
        connectionWatchdog?.cancel()
        connectionWatchdog = lifecycleScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (generation == token && !isConnected && isAttemptActive && !manualStop) {
                retryNext(index, token, "No successful handshake within ${CONNECT_TIMEOUT_MS / 1000} seconds")
            }
        }
    }

    private fun handleVpnStatus(state: String, message: String) {
        val normalized = state.uppercase(Locale.US)
        if (message.isNotBlank()) b.statusMessage.text = message.take(220)
        appendLog("OpenVPN [$normalized]${if (message.isBlank()) "" else ": $message"}")
        if (normalized == "CONNECTED" || normalized.startsWith("CONNECTED,") ||
            (normalized.contains("CONNECTED") && normalized.contains("SUCCESS")) ||
            message.contains("Initialization Sequence Completed", true)) {
            if (isConnected || isVerifying) return
            isVerifying = true
            connectionWatchdog?.cancel()
            val token = generation
            val index = currentIndex
            b.statusTitle.text = "VERIFYING INTERNET"
            b.statusMessage.text = "OpenVPN connected. Checking that HTTPS traffic passes through the tunnel…"
            lifecycleScope.launch {
                delay(900)
                val internetWorks = withContext(Dispatchers.IO) { verifyHttpsTraffic() }
                if (token != generation || manualStop) return@launch
                isVerifying = false
                if (internetWorks) {
                    isConnected = true
                    isAttemptActive = true
                    connectedAtWallClock = System.currentTimeMillis()
                    retryQueued = false
                    b.statusTitle.text = "CONNECTED"
                    b.statusMessage.text = "Tunnel and HTTPS traffic verified with ${currentServer?.displayName ?: "server"}."
                    b.startButton.text = "STOP VPN"
                    appendLog("VPN handshake and HTTPS traffic check succeeded.")
                } else {
                    appendLog("Tunnel handshake appeared successful, but HTTPS traffic verification failed.")
                    retryNext(index, token, "Tunnel connected but HTTPS traffic did not pass through it")
                }
            }
            return
        }
        if (isConnected || isVerifying || !isAttemptActive || manualStop || startedAtElapsed == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
        val terminalFailure = normalized in setOf("AUTH_FAILED", "TLS_ERROR", "EXITING", "CONNECTION_TIMEOUT", "NORSERVER") ||
            message.contains("AUTH_FAILED", true) || message.contains("TLS handshake failed", true) ||
            message.contains("Connection timed out", true)
        if (terminalFailure && elapsed >= EARLY_FAILOVER_MIN_MS) {
            retryNext(currentIndex, generation, message.ifBlank { normalized })
        }
    }

    private fun retryNext(index: Int, expectedGeneration: Int, reason: String) {
        if (expectedGeneration != generation || retryQueued || manualStop || isConnected) return
        retryQueued = true
        connectionWatchdog?.cancel()
        appendLog("Server attempt failed: $reason")
        if (index + 1 >= servers.size || attemptsSinceSelection >= MAX_AUTO_ATTEMPTS) {
            finishFailed("Automatic failover stopped. Last issue: $reason")
            return
        }
        b.statusTitle.text = "SWITCHING SERVER"
        b.statusMessage.text = "This endpoint did not connect. Trying the next server…"
        retryJob = lifecycleScope.launch {
            delay(1_000)
            if (expectedGeneration == generation && !manualStop && !isConnected) {
                startConnectionAtIndex(index + 1, automatic = true)
            }
        }
    }

    private fun disconnectByUser() {
        manualStop = true
        generation++
        connectionWatchdog?.cancel()
        retryJob?.cancel()
        vpnClient.disconnect()
        isAttemptActive = false
        isConnected = false
        isVerifying = false
        retryQueued = false
        startedAtElapsed = 0L
        connectedAtWallClock = 0L
        b.statusTitle.text = "DISCONNECTED"
        b.statusMessage.text = "VPN disconnected by user."
        updateStartButton()
        b.protocolText.text = "Engine: OpenVPN for Android"
        appendLog("Disconnect requested by user.")
    }

    private fun finishFailed(message: String) {
        connectionWatchdog?.cancel()
        isAttemptActive = false
        isConnected = false
        isVerifying = false
        startedAtElapsed = 0L
        connectedAtWallClock = 0L
        retryQueued = false
        vpnClient.disconnect()
        b.statusTitle.text = "CONNECTION FAILED"
        b.statusMessage.text = message
        updateStartButton()
        b.elapsedText.text = "00:00:00"
        appendLog(message)
    }

    private fun handleClientError(message: String) {
        appendLog("OpenVPN API: $message")
        b.statusMessage.text = message
        if (message.contains("not installed", true)) {
            AlertDialog.Builder(this)
                .setTitle("OpenVPN engine required")
                .setMessage("Sari VPN uses the OpenVPN for Android engine for real .ovpn connections. Install that app, then tap Connect again.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open app page") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn")))
                    } catch (_: Exception) {
                        toast("Open the Play Store and search for OpenVPN for Android.")
                    }
                }
                .show()
            isAttemptActive = false
            isConnected = false
            isVerifying = false
            updateStartButton()
            b.statusTitle.text = "ENGINE REQUIRED"
            return
        }
        if (message.contains("not granted", true) || message.contains("permission was not granted", true)) {
            isAttemptActive = false
            b.statusTitle.text = "PERMISSION REQUIRED"
            updateStartButton()
            return
        }
        if (startedAtElapsed > 0L && isAttemptActive && !isConnected) {
            retryNext(currentIndex, generation, message)
        }
    }

    /** The OpenVPN callback proves the handshake; this extra request checks actual tunneled traffic. */
    private fun verifyHttpsTraffic(): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.responseCode == 204
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun appendLog(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val all = (b.diagText.text.toString() + "\n[$stamp] $message").lines().takeLast(65).joinToString("\n")
        b.diagText.text = all.trim()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        connectionWatchdog?.cancel()
        retryJob?.cancel()
        if (::vpnClient.isInitialized) vpnClient.close()
        super.onDestroy()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val EARLY_FAILOVER_MIN_MS = 4_000L
        private const val MAX_AUTO_ATTEMPTS = 8
    }
}
