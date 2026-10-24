package africa.saritunnel

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores user-supplied profile text securely because it can contain private key material. */
object ConfigStore {
    private const val FILE = "sari_vpn_secure_preferences"
    private var cachedPrefs: SharedPreferences? = null

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        val app = context.applicationContext
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { cachedPrefs = it }
    }

    fun feedUrl(context: Context): String = prefs(context).getString("openvpn_feed_url", "").orEmpty()

    fun customProfile(context: Context): String = prefs(context).getString("openvpn_custom_profile", "").orEmpty()

    fun wireGuardConfig(context: Context): String = prefs(context).getString("wireguard_config", "").orEmpty()

    fun saveWireGuardConfig(context: Context, config: String) {
        prefs(context).edit().putString("wireguard_config", config.trim()).apply()
    }

    fun save(context: Context, feedUrl: String, profile: String) {
        prefs(context).edit()
            .putString("openvpn_feed_url", feedUrl.trim())
            .putString("openvpn_custom_profile", profile.trim())
            .apply()
    }
}
