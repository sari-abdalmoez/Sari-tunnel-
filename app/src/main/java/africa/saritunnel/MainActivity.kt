package africa.saritunnel

import africa.saritunnel.databinding.ActivityMainBinding
import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Engine.start()
            } else {
                Toast.makeText(this, "VPN permission is required to connect.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Engine.init(this)

        b.startButton.setOnClickListener { onMainButton() }
        b.openVpnButton.setOnClickListener {
            startActivity(Intent(this, OpenVpnCatalogActivity::class.java))
        }

        b.advancedButton.setOnClickListener {
            startActivity(Intent(this, AdvancedActivity::class.java))
        }
        b.diagToggle.setOnClickListener {
            val show = b.diagPanel.visibility != View.VISIBLE
            b.diagPanel.visibility = if (show) View.VISIBLE else View.GONE
            b.diagToggle.text = if (show) "Hide diagnostics" else "Show diagnostics"
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Engine.state.collect { render(it) } }
                launch { Engine.message.collect { b.statusMessage.text = it } }
                launch {
                    Engine.server.collect { s ->
                        b.serverText.text = if (s == null) "—" else
                            if (s.country.isNotBlank()) "${s.name} · ${s.country}" else s.name
                    }
                }
                launch { Engine.stats.collect { renderStats(it) } }
                launch { Diag.text.collect { b.diagText.text = it } }
                launch {
                    while (isActive) {
                        val t = Engine.connectedAt.value
                        b.elapsedText.text =
                            if (t == 0L) "00:00:00" else formatElapsed(System.currentTimeMillis() - t)
                        delay(1_000)
                    }
                }
            }
        }
    }

    private fun onMainButton() {
        val s = Engine.state.value
        if (s !in setOf(TunnelState.IDLE, TunnelState.ERROR)) {
            Engine.stop()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) consentLauncher.launch(consent) else Engine.start()
    }

    private fun render(s: TunnelState) {
        b.statusTitle.text = when (s) {
            TunnelState.IDLE -> "DISCONNECTED"
            TunnelState.CONNECTED -> "CONNECTED"
            TunnelState.ERROR -> "NOT CONNECTED"
            TunnelState.STOPPING -> "STOPPING"
            else -> s.name.replace('_', ' ')
        }
        val active = s !in setOf(TunnelState.IDLE, TunnelState.ERROR)
        b.startButton.text = if (active) "STOP" else "START"
        b.startButton.isEnabled = s != TunnelState.STOPPING
        val colorRes = when {
            s == TunnelState.CONNECTED -> R.color.sari_green
            active -> R.color.sari_amber
            else -> R.color.sari_blue
        }
        b.startButton.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        if (!active) b.elapsedText.text = "00:00:00"
    }

    private fun renderStats(st: Stats) {
        b.downloadText.text = "↓ ${rate(st.rxSpeed)}"
        b.uploadText.text = "↑ ${rate(st.txSpeed)}"
        b.usageText.text = "Session data: ${size(st.rxTotal + st.txTotal)}"
    }

    private fun rate(bps: Long): String = when {
        bps < 1024 -> "$bps B/s"
        bps < 1024L * 1024 -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
        else -> String.format(Locale.US, "%.2f MB/s", bps / 1048576.0)
    }

    private fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}
