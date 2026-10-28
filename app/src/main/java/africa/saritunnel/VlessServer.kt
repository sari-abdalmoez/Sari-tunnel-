package africa.saritunnel

import org.json.JSONObject

/**
 * One complete vless:// profile from the Blogger feed plus locally measured data.
 *
 * [reachable] is null until the server has been tested. [pingMs] is the TCP connect time measured
 * from this phone; it proves the port answers, not that the tunnel carries traffic. A server is only
 * called "verified" after a real connection passed the in-tunnel HTTPS check ([lastConnectOk]).
 */
data class VlessServer(
    val id: String,
    val name: String,
    val uri: String,
    val host: String,
    val port: Int,
    val country: String,
    val countryCode: String,
    val transport: String,
    val reachable: Boolean? = null,
    val pingMs: Int = -1,
    val testedAt: Long = 0L,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val lastConnectOk: Boolean? = null
) {
    val endpointKey: String get() = "$host:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("uri", uri).put("host", host).put("port", port)
        .put("country", country).put("cc", countryCode).put("transport", transport)
        .put("reachable", if (reachable == null) JSONObject.NULL else reachable)
        .put("ping", pingMs).put("testedAt", testedAt)
        .put("ok", okCount).put("fail", failCount)
        .put("lastOk", if (lastConnectOk == null) JSONObject.NULL else lastConnectOk)

    companion object {
        fun fromJson(o: JSONObject): VlessServer? {
            val uri = o.optString("uri")
            val host = o.optString("host")
            val port = o.optInt("port", 0)
            if (uri.isBlank() || host.isBlank() || port !in 1..65535) return null
            return VlessServer(
                id = o.optString("id").ifBlank { return null },
                name = o.optString("name").ifBlank { "$host:$port" },
                uri = uri,
                host = host,
                port = port,
                country = o.optString("country").ifBlank { "Unknown" },
                countryCode = o.optString("cc"),
                transport = o.optString("transport"),
                reachable = if (o.isNull("reachable")) null else o.optBoolean("reachable"),
                pingMs = o.optInt("ping", -1),
                testedAt = o.optLong("testedAt", 0L),
                okCount = o.optInt("ok", 0),
                failCount = o.optInt("fail", 0),
                lastConnectOk = if (o.isNull("lastOk")) null else o.optBoolean("lastOk")
            )
        }
    }
}
