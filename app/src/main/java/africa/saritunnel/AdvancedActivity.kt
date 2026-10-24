package africa.saritunnel

import africa.saritunnel.databinding.ActivityAdvancedBinding
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URL

class AdvancedActivity : AppCompatActivity() {
    private lateinit var b: ActivityAdvancedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAdvancedBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.feedInput.setText(
            ConfigStore.feedUrl(this).ifBlank { OpenVpnServerRepository.DEFAULT_FEED_URL }
        )
        b.configInput.setText(ConfigStore.customProfile(this))

        b.saveButton.setOnClickListener { saveSettings() }
        b.clearButton.setOnClickListener {
            b.feedInput.setText("")
            b.configInput.setText("")
            ConfigStore.save(this, "", "")
            toast("Custom settings cleared. The default backup feed will be used.")
        }
    }

    private fun saveSettings() {
        val feed = b.feedInput.text.toString().trim()
        val profile = b.configInput.text.toString().trim()

        if (feed.isNotBlank()) {
            val parsed = try { URL(feed) } catch (_: Exception) {
                toast("Enter a valid HTTPS JSON feed URL.")
                return
            }
            if (!parsed.protocol.equals("https", ignoreCase = true)) {
                toast("The backup feed must use HTTPS.")
                return
            }
        }

        if (profile.length > 1_000_000) {
            toast("The OpenVPN profile is too large.")
            return
        }
        if (profile.isNotBlank()) {
            try {
                OpenVpnConfigParser.prepare(profile)
            } catch (e: Exception) {
                toast(e.message ?: "The OpenVPN profile is invalid.")
                return
            }
        }

        ConfigStore.save(this, feed, profile)
        toast("Sari VPN settings saved securely.")
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
