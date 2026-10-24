package africa.saritunnel

import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/** Runs the real WireGuard Go userspace backend with a user-provided complete config. */
class WireGuardActivity : AppCompatActivity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var backend: GoBackend
    private var pendingConfig: Config? = null
    private var busy = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = "SariTunnel-WireGuard"
        override fun onStateChange(newState: Tunnel.State) { runOnUiThread { if (newState == Tunnel.State.UP) setStatus("WireGuard engine reports tunnel UP") } }
    }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = pendingConfig
        pendingConfig = null
        if (result.resultCode == RESULT_OK && config != null) connect(config)
        else setStatus("Android VPN permission was not granted.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)
        editor = EditText(this).apply {
            hint = "Paste a complete WireGuard [Interface] and [Peer] configuration"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(ConfigStore.wireGuardConfig(this@WireGuardActivity))
            setTextColor(ContextCompat.getColor(this@WireGuardActivity, R.color.sari_text))
            setHintTextColor(ContextCompat.getColor(this@WireGuardActivity, R.color.sari_muted))
        }
        status = TextView(this).apply {
            text = "WireGuard uses its own real Go tunnel engine. Add your own valid configuration."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@WireGuardActivity, R.color.sari_text))
            setPadding(0, dp(10), 0, dp(10))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setBackgroundColor(ContextCompat.getColor(this@WireGuardActivity, R.color.sari_background))
        }
        content.addView(TextView(this).apply {
            text = "WireGuard"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WireGuardActivity, R.color.sari_text))
        })
        content.addView(TextView(this).apply {
            text = "Paste a full WireGuard configuration from your server provider. An IP address alone is not a configuration."
            textSize = 12f
            setPadding(0, dp(5), 0, dp(8))
            setTextColor(ContextCompat.getColor(this@WireGuardActivity, R.color.sari_muted))
        })
        content.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(status)
        content.addView(Button(this).apply {
            text = "Connect WireGuard"
            setOnClickListener { prepareConnect() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(Button(this).apply {
            text = "Disconnect WireGuard"
            setOnClickListener { disconnect() }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        setContentView(ScrollView(this).apply { addView(content, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.MATCH_PARENT)) })
    }

    private fun prepareConnect() {
        if (busy) return
        val raw = editor.text.toString().trim()
        if (raw.isBlank()) {
            setStatus("Paste a complete WireGuard configuration first.")
            return
        }
        lifecycleScope.launch {
            val parsed = try {
                withContext(Dispatchers.IO) { Config.parse(BufferedReader(StringReader(raw))) }
            } catch (e: Exception) {
                setStatus("Invalid WireGuard configuration: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            ConfigStore.saveWireGuardConfig(this@WireGuardActivity, raw)
            pendingConfig = parsed
            val consent = VpnService.prepare(this@WireGuardActivity)
            if (consent == null) {
                pendingConfig = null
                connect(parsed)
            } else consentLauncher.launch(consent)
        }
    }

    private fun connect(config: Config) {
        if (busy) return
        busy = true
        setStatus("Starting WireGuard tunnel…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, config) }
                setStatus("WireGuard backend accepted the configuration. Check the Android VPN indicator and test a website.")
            } catch (e: Exception) {
                setStatus("WireGuard connection failed: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = false }
        }
    }

    private fun disconnect() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                setStatus("WireGuard disconnected.")
            } catch (e: Exception) {
                setStatus("Could not stop WireGuard: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = false }
        }
    }

    private fun setStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        super.onDestroy()
    }
}
