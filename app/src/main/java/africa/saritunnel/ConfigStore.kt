package africa.saritunnel

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted storage for user configs and cached feeds (feeds contain private keys). */
object ConfigStore {
    private const val FILE = "saritunnel_secure_prefs"
    private var cached: SharedPreferences? = null

    @Synchronized
    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        val app = ctx.applicationContext
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val p = EncryptedSharedPreferences.create(
            app,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        cached = p
        return p
    }

    fun customConfig(ctx: Context): String? =
        prefs(ctx).getString("custom_config", null)?.takeIf { it.isNotBlank() }

    fun feedUrl(ctx: Context): String = prefs(ctx).getString("feed_url", "").orEmpty()

    fun save(ctx: Context, feed: String, config: String) {
        prefs(ctx).edit()
            .putString("feed_url", feed)
            .putString("custom_config", config)
            .apply()
    }

    /** Returns Triple(url, cachedAtMillis, json) or null. */
    fun feedCache(ctx: Context): Triple<String, Long, String>? {
        val p = prefs(ctx)
        val url = p.getString("cache_url", null) ?: return null
        val json = p.getString("cache_json", null) ?: return null
        return Triple(url, p.getLong("cache_at", 0L), json)
    }

    fun putFeedCache(ctx: Context, url: String, json: String) {
        prefs(ctx).edit()
            .putString("cache_url", url)
            .putLong("cache_at", System.currentTimeMillis())
            .putString("cache_json", json)
            .apply()
    }
}
