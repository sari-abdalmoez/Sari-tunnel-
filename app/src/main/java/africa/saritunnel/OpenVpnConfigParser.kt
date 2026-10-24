package africa.saritunnel

import java.io.IOException

 data class PreparedOpenVpnConfig(
    val text: String,
    val host: String,
    val port: Int,
    val protocol: String
)

/** Validates a single-file OpenVPN profile without stripping or rewriting inline certificates. */
object OpenVpnConfigParser {
    private const val MAX_PROFILE_CHARS = 1_000_000
    private val remoteRegex = Regex(
        "(?im)^\\s*remote\\s+([^\\s#]+)(?:\\s+(\\d{1,5}))?(?:\\s+(udp|udp4|udp6|tcp|tcp-client|tcp4-client|tcp6-client))?\\s*(?:#.*)?$"
    )

    fun prepare(raw: String): PreparedOpenVpnConfig {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw IOException("The downloaded OpenVPN profile is empty.")
        if (text.length > MAX_PROFILE_CHARS) throw IOException("The OpenVPN profile is unexpectedly large.")
        if (text.contains("<html", ignoreCase = true) || text.contains("Just a moment", ignoreCase = true)) {
            throw IOException("The server returned a web page instead of an .ovpn profile.")
        }
        if (!Regex("(?im)^\\s*client\\s*$").containsMatchIn(text) &&
            !Regex("(?im)^\\s*remote\\s+").containsMatchIn(text)
        ) {
            throw IOException("This file does not look like an OpenVPN client profile.")
        }

        val remote = remoteRegex.find(text)
            ?: throw IOException("The profile has no usable 'remote host port' endpoint.")
        val host = remote.groupValues[1].trim('[', ']')
        val port = remote.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 1194
        val protocol = remote.groupValues[3].ifBlank {
            Regex("(?im)^\\s*proto\\s+(udp6?|tcp(?:-client|4-client|6-client)?)\\s*$")
                .find(text)?.groupValues?.get(1) ?: "udp"
        }
        if (host.isBlank() || host.length > 253 || port !in 1..65535) {
            throw IOException("The OpenVPN endpoint is invalid.")
        }

        ensureInlineAssets(text)

        val options = mutableListOf<String>()
        if (!hasDirective(text, "connect-timeout")) options += "connect-timeout 8"
        if (!hasDirective(text, "connect-retry")) options += "connect-retry 2 5"
        if (!hasDirective(text, "resolv-retry")) options += "resolv-retry 10"
        val prepared = if (options.isEmpty()) text else text.trimEnd() + "\n\n# Sari VPN resilience options\n" + options.joinToString("\n") + "\n"
        return PreparedOpenVpnConfig(prepared, host, port, protocol)
    }

    private fun hasDirective(text: String, name: String): Boolean =
        Regex("(?im)^\\s*${Regex.escape(name)}(?:\\s|$)").containsMatchIn(text)

    private fun ensureInlineAssets(text: String) {
        val assets = listOf("ca", "cert", "key", "tls-auth", "tls-crypt", "pkcs12")
        for (directive in assets) {
            val blockName = when (directive) {
                "tls-auth" -> "tls-auth"
                "tls-crypt" -> "tls-crypt"
                else -> directive
            }
            val hasInlineBlock = Regex("(?is)<${Regex.escape(blockName)}>.*?</${Regex.escape(blockName)}>")
                .containsMatchIn(text)
            val hasExternalReference = Regex("(?im)^\\s*${Regex.escape(directive)}\\s+([^\\s#]+)")
                .find(text)?.groupValues?.getOrNull(1)
                ?.let { !it.startsWith("<") } == true
            if (hasExternalReference && !hasInlineBlock) {
                throw IOException("This profile refers to a separate '$directive' file. Sari VPN can auto-connect single-file profiles with embedded certificates only.")
            }
        }
    }
}
