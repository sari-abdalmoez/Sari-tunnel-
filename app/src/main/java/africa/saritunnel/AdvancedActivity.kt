package africa.saritunnel

import africa.saritunnel.databinding.ActivityAdvancedBinding
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

class AdvancedActivity : AppCompatActivity() {

    private lateinit var b: ActivityAdvancedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAdvancedBinding.inflate(layoutInflater)
        setContentView(b.root)

        lifecycleScope.launch {
            val (feed, cfg) = withContext(Dispatchers.IO) {
                ConfigStore.feedUrl(this@AdvancedActivity) to
                    ConfigStore.customConfig(this@AdvancedActivity).orEmpty()
            }
            b.feedInput.setText(feed)
            b.configInput.setText(cfg)
        }

        b.saveButton.setOnClickListener { save() }
        b.clearButton.setOnClickListener {
            b.feedInput.setText("")
            b.configInput.setText("")
            save()
        }
    }

    private fun save() {
        val feed = b.feedInput.text.toString().trim()
        val cfg = b.configInput.text.toString().trim()

        if (feed.isNotEmpty() && !feed.startsWith("https://")) {
            toast("Feed URL must start with https://")
            return
        }
        if (cfg.length > 8192) {
            toast("Configuration is too large")
            return
        }
        if (cfg.isNotEmpty()) {
            try {
                Config.parse(BufferedReader(StringReader(cfg)))
            } catch (e: Exception) {
                toast("Invalid WireGuard configuration")
                return
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            ConfigStore.save(this@AdvancedActivity, feed, cfg)
        }
        toast("Saved")
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }
}
