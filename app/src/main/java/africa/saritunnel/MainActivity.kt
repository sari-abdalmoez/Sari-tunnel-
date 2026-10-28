package africa.saritunnel

import africa.saritunnel.databinding.ActivityMainBinding
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Home / Location / Settings screens for the VLESS (Xray) tunnel.
 * OpenVPN and Tor controls live on [OpenVpnActivity] and are reachable from Settings.
 */
class MainActivity : AppCompatActivity() {
    private enum class Tab { HOME, LOCATION, SETTINGS }

    private lateinit var b: ActivityMainBinding
    private lateinit var manager: VlessSubscriptionManager
    private lateinit var adapter: ServerAdapter
    private val prefs by lazy { getSharedPreferences("sari_home_prefs", MODE_PRIVATE) }

    private var servers: List<VlessServer> = emptyList()
    private var fetchedAt = 0L
    private var lastError: String? = null

    /** null means "Auto": the fastest server found by the latency test. */
    private var selectedId: String? = null
    private var sortMode = SortMode.FASTEST
    private val collapsed = mutableSetOf<String>()
    private var tab = Tab.HOME

    private var refreshing = false
    private var testing = false
    private var testDone = 0
    private var testTotal = 0
    private var testJob: Job? = null
    private var lastListRender = 0L

    private var pendingServer: VlessServer? = null
    private val autoQueue = ArrayDeque<VlessServer>()
    private var userWantsConnection = false
    private var lastState = TunnelState.State()
    private var logVisible = false

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val server = pendingServer
        pendingServer = null
        if (result.resultCode == RESULT_OK && server != null) {
            startTunnel(server)
        } else {
            userWantsConnection = false
            toast("Android VPN permission is required to connect.")
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.rootLayout.applySystemBarInsets()

        manager = VlessSubscriptionManager(this)
        SubscriptionUpdateWorker.schedulePeriodicUpdates(this)

        selectedId = prefs.getString(KEY_SELECTED, null)
        sortMode = runCatching { SortMode.valueOf(prefs.getString(KEY_SORT, SortMode.FASTEST.name)!!) }
            .getOrDefault(SortMode.FASTEST)

        adapter = ServerAdapter(
            onSelect = ::onServerPicked,
            onCopy = ::copyLink,
            onToggleCountry = { code ->
                if (!collapsed.add(code)) collapsed.remove(code)
                renderList()
            }
        )
        b.serverList.layoutManager = LinearLayoutManager(this)
        b.serverList.adapter = adapter

        b.navHome.setOnClickListener { showTab(Tab.HOME) }
        b.navLocation.setOnClickListener { showTab(Tab.LOCATION) }
        b.navSettings.setOnClickListener { showTab(Tab.SETTINGS) }
        b.connectButton.setOnClickListener { onPowerTapped() }
        b.tvHint.setOnClickListener { onPowerTapped() }
        b.cardServer.setOnClickListener { showTab(Tab.LOCATION) }
        b.btnRefresh.setOnClickListener { refreshAndTest(manual = true) }
        b.btnRefreshNow.setOnClickListener { refreshAndTest(manual = true) }
        b.btnTestAll.setOnClickListener { testServers(manual = true) }
        b.btnRetest.setOnClickListener { testServers(manual = true) }
        b.chipFastest.setOnClickListener { setSort(SortMode.FASTEST) }
        b.chipBest.setOnClickListener { setSort(SortMode.BEST) }
        b.chipCountry.setOnClickListener { setSort(SortMode.COUNTRY) }
        b.btnOpenVpn.setOnClickListener { startActivity(Intent(this, OpenVpnActivity::class.java)) }
        b.btnAdvanced.setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java)) }
        b.btnLogToggle.setOnClickListener {
            logVisible = !logVisible
            b.tvLog.visibility = if (logVisible) View.VISIBLE else View.GONE
            b.btnLogToggle.text = if (logVisible) "Hide connection log" else "Show connection log"
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tab != Tab.HOME) showTab(Tab.HOME) else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Cached servers are shown immediately, before any network request.
        loadCache()
        showTab(Tab.HOME)
        render()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TunnelState.state.collect { onTunnelState(it) } }
                launch { TunnelState.log.collect { lines -> b.tvLog.text = lines.joinToString("\n") } }
                launch {
                    while (isActive) {
                        updateTimer()
                        delay(1_000)
                    }
                }
            }
        }

        startupWork()
    }

    override fun onStart() {
        super.onStart()
        // Coming back after a long time: refresh when the 24-hour window has passed.
        if (::manager.isInitialized && !refreshing && !TunnelState.isActive() && manager.shouldRefresh()) {
            refreshAndTest(manual = false)
        }
    }

    // ------------------------------------------------------------------ data

    private fun loadCache() {
        val snap = manager.loadSnapshot()
        servers = snap.servers
        fetchedAt = snap.fetchedAt
        lastError = snap.lastError
        if (selectedId != null && servers.none { it.id == selectedId }) {
            selectedId = null
            prefs.edit().remove(KEY_SELECTED).apply()
        }
    }

    private fun startupWork() {
        val snap = manager.loadSnapshot()
        when {
            manager.shouldRefresh(snap) -> refreshAndTest(manual = false)
            servers.isNotEmpty() && !TunnelState.isActive() && needsRetest() -> testServers(manual = false)
        }
    }

    private fun needsRetest(): Boolean {
        val newest = servers.maxOfOrNull { it.testedAt } ?: 0L
        return servers.any { it.reachable == null } || System.currentTimeMillis() - newest > RETEST_AFTER_MS
    }

    private fun refreshAndTest(manual: Boolean) {
        if (refreshing) return
        refreshing = true
        TunnelState.log("Refreshing servers from the Blogger source…")
        render()
        lifecycleScope.launch {
            val result = manager.refresh()
            val oldSelected = selectedId
            loadCache()
            refreshing = false
            when (result) {
                is VlessSubscriptionManager.RefreshResult.Ok -> {
                    TunnelState.log("Server list updated: ${result.count} server(s).")
                    if (manual) toast("${result.count} servers loaded.")
                }
                is VlessSubscriptionManager.RefreshResult.Error -> {
                    TunnelState.log("Refresh failed: ${result.message}. Keeping ${servers.size} cached server(s).")
                    if (manual) toast(
                        if (servers.isEmpty()) "Could not load servers: ${result.message}"
                        else "Refresh failed. Showing ${servers.size} cached servers."
                    )
                }
            }
            if (oldSelected != null && selectedId == null) TunnelState.log("The previously selected server is no longer listed; using Auto.")
            render()
            if (servers.isNotEmpty() && !TunnelState.isActive()) testServers(manual = false)
        }
    }

    private fun testServers(manual: Boolean) {
        if (testing) return
        if (servers.isEmpty()) {
            if (manual) toast("No servers to test yet.")
            return
        }
        if (TunnelState.isActive()) {
            if (manual) toast("Disconnect first. Ping measured through the VPN would be wrong.")
            return
        }
        testing = true
        testDone = 0
        testTotal = servers.map { it.endpointKey }.toSet().size
        val partial = ConcurrentHashMap<String, LatencyTester.Result>()
        TunnelState.log("Testing TCP latency of $testTotal endpoint(s)…")
        render()
        testJob = lifecycleScope.launch {
            try {
                LatencyTester.test(servers) { key, result ->
                    partial[key] = result
                    val now = System.currentTimeMillis()
                    servers = servers.map { s ->
                        if (s.endpointKey == key) s.copy(reachable = result.reachable, pingMs = result.pingMs, testedAt = now) else s
                    }
                    testDone++
                    if (now - lastListRender > 300 || testDone == testTotal) {
                        lastListRender = now
                        render()
                    }
                }
            } finally {
                manager.saveLatency(partial)
                testing = false
                val ok = servers.count { it.reachable == true }
                TunnelState.log("Latency test finished: $ok of ${servers.size} server(s) answered.")
                render()
            }
        }
    }

    // --------------------------------------------------------------- actions

    private fun onPowerTapped() {
        when (lastState.phase) {
            TunnelState.Phase.CONNECTING, TunnelState.Phase.VERIFYING, TunnelState.Phase.CONNECTED -> disconnect()
            TunnelState.Phase.DISCONNECTING -> Unit
            else -> if (userWantsConnection) {
                // Tapped while waiting for the latency test: cancel the pending connect.
                userWantsConnection = false
                render()
            } else connect()
        }
    }

    private fun connect() {
        if (servers.isEmpty()) {
            toast("No servers yet. Updating the list…")
            refreshAndTest(manual = false)
            return
        }
        userWantsConnection = true
        autoQueue.clear()
        val chosen = selectedId?.let { id -> servers.firstOrNull { it.id == id } }
        if (chosen != null) {
            if (testing) testJob?.cancel()
            requestConnect(chosen)
            return
        }
        // Auto mode: only a server that answered the latency test can be called the fastest.
        render()
        lifecycleScope.launch {
            if (servers.none { it.reachable == true }) {
                if (!testing) testServers(manual = false)
                testJob?.join()
            }
            if (!userWantsConnection) return@launch
            val ranked = servers.filter { it.reachable == true }.sortedBy { it.pingMs }
            if (ranked.isEmpty()) {
                userWantsConnection = false
                toast("No server answered the ping test. Check your network or refresh the list.")
                render()
                return@launch
            }
            autoQueue.addAll(ranked.drop(1).take(MAX_AUTO_FALLBACKS))
            requestConnect(ranked.first())
        }
    }

    private fun requestConnect(server: VlessServer) {
        val consent = VpnService.prepare(this)
        if (consent == null) {
            startTunnel(server)
        } else {
            pendingServer = server
            vpnConsent.launch(consent)
        }
    }

    private fun startTunnel(server: VlessServer) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_START)
                    .putExtra(XrayVpnService.EXTRA_URI, server.uri)
                    .putExtra(XrayVpnService.EXTRA_SERVER_ID, server.id)
                    .putExtra(XrayVpnService.EXTRA_SERVER_NAME, server.name)
            )
            TunnelState.update(TunnelState.Phase.CONNECTING, server.id, "Connecting to ${server.name}…")
        } catch (e: Exception) {
            userWantsConnection = false
            val msg = "Could not start the VPN service: ${e.message ?: e.javaClass.simpleName}"
            TunnelState.update(TunnelState.Phase.FAILED, server.id, msg)
            TunnelState.log(msg)
        }
    }

    private fun disconnect() {
        userWantsConnection = false
        autoQueue.clear()
        try {
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
            TunnelState.update(TunnelState.Phase.DISCONNECTING, message = "Disconnecting…")
        } catch (e: Exception) {
            toast("Could not stop the VPN: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun onServerPicked(server: VlessServer?) {
        val activeNow = TunnelState.isActive()
        if (server == null) {
            selectedId = null
            prefs.edit().remove(KEY_SELECTED).apply()
            if (activeNow) toast("Auto applies the next time you connect.")
            showTab(Tab.HOME)
            render()
            return
        }
        val differentFromActive = activeNow && lastState.serverId != server.id
        selectedId = server.id
        prefs.edit().putString(KEY_SELECTED, server.id).apply()
        showTab(Tab.HOME)
        render()
        if (differentFromActive) {
            AlertDialog.Builder(this)
                .setTitle("Switch server?")
                .setMessage("Reconnect through ${server.name} (${server.country}) now?")
                .setNegativeButton("Later", null)
                .setPositiveButton("Switch") { _, _ ->
                    userWantsConnection = true
                    autoQueue.clear()
                    requestConnect(server)
                }
                .show()
        }
    }

    private fun copyLink(server: VlessServer) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SariTunnel server", server.uri))
        toast("Server link copied.")
    }

    private fun setSort(mode: SortMode) {
        sortMode = mode
        prefs.edit().putString(KEY_SORT, mode.name).apply()
        render()
    }

    // ----------------------------------------------------------------- state

    private fun onTunnelState(state: TunnelState.State) {
        val previous = lastState.phase
        lastState = state
        if (state.phase == TunnelState.Phase.FAILED && userWantsConnection && autoQueue.isNotEmpty()) {
            val next = autoQueue.removeFirst()
            TunnelState.log("Trying the next fastest server: ${next.name}.")
            requestConnect(next)
        } else if (state.phase == TunnelState.Phase.FAILED || state.phase == TunnelState.Phase.IDLE) {
            userWantsConnection = false
        }
        // The service records connection results; pick them up for the "best performing" ranking.
        if (previous != state.phase && !testing &&
            (state.phase == TunnelState.Phase.CONNECTED || state.phase == TunnelState.Phase.FAILED)
        ) {
            val snap = manager.loadSnapshot()
            if (snap.servers.isNotEmpty()) servers = snap.servers
        }
        render()
    }

    private fun updateTimer() {
        if (lastState.phase == TunnelState.Phase.CONNECTED && lastState.connectedAtMs > 0L) {
            val s = ((System.currentTimeMillis() - lastState.connectedAtMs) / 1000).coerceAtLeast(0)
            b.tvHint.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        }
    }

    // -------------------------------------------------------------- rendering

    private fun showTab(target: Tab) {
        tab = target
        b.panelHome.visibility = if (target == Tab.HOME) View.VISIBLE else View.GONE
        b.panelLocation.visibility = if (target == Tab.LOCATION) View.VISIBLE else View.GONE
        b.panelSettings.visibility = if (target == Tab.SETTINGS) View.VISIBLE else View.GONE
        b.navHome.isSelected = target == Tab.HOME
        b.navLocation.isSelected = target == Tab.LOCATION
        b.navSettings.isSelected = target == Tab.SETTINGS
    }

    private fun byId(id: String?): VlessServer? = if (id == null) null else servers.firstOrNull { it.id == id }

    /** The fastest server that actually answered a latency test, or null if none has. */
    private fun fastestTested(): VlessServer? =
        servers.filter { it.reachable == true }.minByOrNull { it.pingMs }

    private fun render() {
        if (!::b.isInitialized) return
        renderHome()
        renderList()
        renderSettings()
    }

    private fun renderHome() {
        val phase = lastState.phase
        val active = byId(lastState.serverId)
        val chosen = byId(selectedId)
        val auto = fastestTested()
        val target = chosen ?: auto

        val waitingForTest = userWantsConnection && phase == TunnelState.Phase.IDLE && testing
        val busy = phase == TunnelState.Phase.CONNECTING || phase == TunnelState.Phase.VERIFYING ||
            phase == TunnelState.Phase.DISCONNECTING || waitingForTest

        when {
            phase == TunnelState.Phase.CONNECTED -> setStatus("You are protected", R.color.sari_green, R.drawable.ic_shield_on)
            phase == TunnelState.Phase.VERIFYING -> setStatus("Checking connection…", R.color.sari_amber, R.drawable.ic_shield_wait)
            phase == TunnelState.Phase.CONNECTING || waitingForTest -> setStatus("Connecting…", R.color.sari_amber, R.drawable.ic_shield_wait)
            phase == TunnelState.Phase.DISCONNECTING -> setStatus("Disconnecting…", R.color.sari_amber, R.drawable.ic_shield_wait)
            else -> setStatus("You are unprotected!", R.color.sari_danger, R.drawable.ic_shield_off)
        }

        b.connectButton.mode = when {
            phase == TunnelState.Phase.CONNECTED -> ConnectButtonView.Mode.CONNECTED
            busy -> ConnectButtonView.Mode.BUSY
            else -> ConnectButtonView.Mode.IDLE
        }

        val shown = if (phase == TunnelState.Phase.CONNECTED || phase == TunnelState.Phase.CONNECTING ||
            phase == TunnelState.Phase.VERIFYING) active ?: target else target
        b.tvLocationLabel.text = if (phase == TunnelState.Phase.CONNECTED) "Current Location:" else "Selected Location:"
        b.tvLocation.text = when {
            shown != null -> shown.country
            servers.isEmpty() -> if (refreshing) "Loading…" else "No servers"
            testing -> "Testing…"
            else -> "Auto"
        }

        if (phase != TunnelState.Phase.CONNECTED) {
            b.tvHint.text = when {
                phase == TunnelState.Phase.CONNECTING || phase == TunnelState.Phase.VERIFYING -> "Tap to cancel"
                phase == TunnelState.Phase.DISCONNECTING -> "Disconnecting…"
                waitingForTest -> "Finding fastest server…"
                phase == TunnelState.Phase.FAILED -> "Tap to retry"
                else -> "Tap to connect"
            }
        } else updateTimer()

        // Bottom card: "Fastest" is only claimed for a server that was really tested.
        when {
            chosen != null -> {
                b.tvCardLabel.text = if (chosen.reachable == true) "Selected Location · ${chosen.pingMs} ms" else "Selected Location"
                b.tvCardName.text = chosen.name
                b.tvCardFlag.text = CountryUtils.flag(chosen.countryCode)
            }
            auto != null -> {
                b.tvCardLabel.text = "Fastest Location · ${auto.pingMs} ms"
                b.tvCardName.text = auto.name
                b.tvCardFlag.text = CountryUtils.flag(auto.countryCode)
            }
            else -> {
                b.tvCardLabel.text = if (testing) "Finding fastest location…" else "Fastest Location"
                b.tvCardName.text = when {
                    servers.isEmpty() -> "No servers loaded"
                    testing -> "Testing $testDone/$testTotal"
                    else -> "Not tested yet"
                }
                b.tvCardFlag.text = "\uD83C\uDF10"
            }
        }

        b.tvInfo.text = when {
            phase == TunnelState.Phase.FAILED -> lastState.message
            phase == TunnelState.Phase.CONNECTED -> "Traffic through the tunnel was verified."
            testing -> "Testing servers $testDone/$testTotal…"
            refreshing -> "Updating the server list…"
            servers.isEmpty() -> lastError?.let { "Could not load servers: $it" } ?: "No servers cached yet."
            else -> updatedText()
        }

        val working = refreshing || testing
        b.btnRefresh.alpha = if (working) 0.45f else 1f
        b.btnRefresh.isEnabled = !working
    }

    private fun renderList() {
        val items = ServerSorter.build(servers, sortMode, collapsed)
        val autoText = fastestTested()?.let { "Currently ${it.name} · ${it.pingMs} ms" }
            ?: "Picks the lowest ping after a test"
        adapter.submit(items, selectedId, lastState.serverId, lastState.phase, autoText)

        b.chipFastest.isSelected = sortMode == SortMode.FASTEST
        b.chipBest.isSelected = sortMode == SortMode.BEST
        b.chipCountry.isSelected = sortMode == SortMode.COUNTRY

        val working = refreshing || testing
        b.listProgress.visibility = if (working) View.VISIBLE else View.INVISIBLE
        b.btnTestAll.isEnabled = !testing
        b.tvEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        b.serverList.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
        b.tvEmpty.text = when {
            refreshing -> "Loading servers…"
            lastError != null -> "No servers cached yet.\n${lastError}\nTap the refresh button on Home to retry."
            else -> "No servers cached yet."
        }
        b.tvListInfo.text = when {
            testing -> "Testing ping $testDone/$testTotal… (TCP connect time)"
            else -> "${updatedText()} · ping = TCP connect time; it is not a speed test."
        }
    }

    private fun renderSettings() {
        val sb = StringBuilder()
        sb.append(VlessSubscriptionManager.SOURCE_URL).append('\n')
        sb.append(updatedText()).append('\n')
        sb.append("Auto-refresh every 24 hours; cached servers are kept if the network fails.")
        lastError?.let { sb.append("\nLast refresh problem: ").append(it) }
        b.tvSourceInfo.text = sb.toString()
        b.btnRefreshNow.isEnabled = !refreshing
        b.btnRetest.isEnabled = !testing
        b.tvAbout.text = "SariTunnel Africa ${BuildConfigInfo.versionName(this)} · Android allows one active VPN at a time. " +
            "Public servers may be slow or unavailable. \"Connected\" is shown only after traffic passes through the tunnel."
    }

    private fun updatedText(): String {
        if (servers.isEmpty()) return "No cached servers"
        val ago = if (fetchedAt > 0L) DateUtils.getRelativeTimeSpanString(
            fetchedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString() else "never"
        return "${servers.size} servers · updated $ago"
    }

    private fun setStatus(text: String, colorRes: Int, iconRes: Int) {
        b.tvStatus.text = text
        b.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        b.ivShield.setImageResource(iconRes)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        testJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val KEY_SELECTED = "selected_server_id"
        private const val KEY_SORT = "sort_mode"
        private const val RETEST_AFTER_MS = 30L * 60 * 1000
        private const val MAX_AUTO_FALLBACKS = 2
    }
}

/** Reads the version without depending on a generated BuildConfig class. */
private object BuildConfigInfo {
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
