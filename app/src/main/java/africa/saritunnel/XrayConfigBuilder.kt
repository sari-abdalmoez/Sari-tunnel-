package africa.saritunnel

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Builds a restrictive Xray TUN config from a complete VLESS share URI. */
object XrayConfigBuilder {
    /**
     * [resolvedAddress] lets the caller connect to a pre-resolved IP while TLS/REALITY server names and
     * HTTP Host headers still use the original host name. Go's resolver cannot read Android's DNS
     * settings inside the sandbox, so resolving before the tunnel starts is the reliable path.
     */
    fun fromVlessUri(input: String, resolvedAddress: String? = null): String {
        val raw = input.trim()
        require(raw.startsWith("vless://", ignoreCase = true)) { "Only vless:// profiles are accepted here." }
        val uri = Uri.parse(raw)
        val uuid = Uri.decode(uri.userInfo.orEmpty()).substringBefore(':').trim()
        val host = uri.host?.trim().orEmpty()
        val port = uri.port
        require(uuid.isNotBlank() && '@' in raw) { "The VLESS link is missing its user ID." }
        require(host.isNotBlank() && port in 1..65535) { "The VLESS link needs a host and port." }
        require(!raw.contains("...") && !raw.contains('…')) { "This VLESS link looks truncated. Publish the complete link." }

        // "raw" is the newer Xray name for plain TCP and is used by the Blogger feed.
        val network = (uri.getQueryParameter("type") ?: "tcp").lowercase(Locale.ROOT).let { if (it == "raw") "tcp" else it }
        val security = (uri.getQueryParameter("security") ?: "none").lowercase(Locale.ROOT)
        val sni = uri.getQueryParameter("sni")?.takeIf { it.isNotBlank() }
            ?: uri.getQueryParameter("serverName")?.takeIf { it.isNotBlank() } ?: host
        val flow = uri.getQueryParameter("flow")?.takeIf { it.isNotBlank() }
        require(network in setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
            "Unsupported VLESS transport '$network'. Supported: tcp, ws, grpc, httpupgrade, xhttp."
        }
        require(security in setOf("none", "tls", "reality")) {
            "Unsupported VLESS security '$security'."
        }
        if (flow != null) require(network == "tcp" && security in setOf("tls", "reality")) {
            "VLESS flow requires TCP with TLS or REALITY."
        }

        val user = JSONObject().put("id", uuid).put("encryption", "none")
        if (flow != null) user.put("flow", flow)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", resolvedAddress?.takeIf { it.isNotBlank() } ?: host).put("port", port).put("users", JSONArray().put(user))
            )))

        val hostParam = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() }
            ?: if (!resolvedAddress.isNullOrBlank()) host else null
        val stream = JSONObject().put("network", network).put("security", security)
        when (network) {
            "ws" -> {
                val headers = JSONObject()
                hostParam?.let { headers.put("Host", it) }
                val ws = JSONObject().put("path", uri.getQueryParameter("path")?.ifBlank { "/" } ?: "/")
                if (headers.length() > 0) ws.put("headers", headers)
                stream.put("wsSettings", ws)
            }
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", uri.getQueryParameter("serviceName") ?: "")
                .put("authority", uri.getQueryParameter("authority") ?: sni)
                .put("multiMode", uri.getQueryParameter("mode").equals("multi", true)))
            "httpupgrade" -> {
                val settings = JSONObject().put("path", uri.getQueryParameter("path")?.ifBlank { "/" } ?: "/")
                hostParam?.let { settings.put("host", it) }
                stream.put("httpupgradeSettings", settings)
            }
            "xhttp" -> {
                val settings = JSONObject().put("path", uri.getQueryParameter("path")?.ifBlank { "/" } ?: "/")
                hostParam?.let { settings.put("host", it) }
                uri.getQueryParameter("mode")?.takeIf { it.isNotBlank() }?.let { settings.put("mode", it) }
                stream.put("xhttpSettings", settings)
            }
        }

        when (security) {
            "tls" -> {
                val tls = JSONObject().put("serverName", sni).put("allowInsecure", false)
                uri.getQueryParameter("alpn")?.takeIf { it.isNotBlank() }?.let { v ->
                    tls.put("alpn", JSONArray(v.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                }
                uri.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                val publicKey = uri.getQueryParameter("pbk")?.takeIf { it.isNotBlank() }
                    ?: uri.getQueryParameter("publicKey")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("REALITY link is missing pbk/publicKey.")
                val reality = JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", uri.getQueryParameter("fp")?.ifBlank { "chrome" } ?: "chrome")
                    .put("publicKey", publicKey)
                    .put("shortId", uri.getQueryParameter("sid") ?: "")
                    .put("spiderX", uri.getQueryParameter("spx") ?: "/")
                stream.put("realitySettings", reality)
            }
        }
        outbound.put("streamSettings", stream)

        val inbounds = JSONArray().put(JSONObject()
            .put("tag", "sari-tun")
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "saritun").put("mtu", 1280)))
        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", JSONArray().put(outbound))
        return root.toString()
    }
}
