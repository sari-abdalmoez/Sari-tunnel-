package africa.saritunnel

import java.util.Locale

/** Offline country detection from labels, server names and host names, plus emoji flags (no image assets). */
object CountryUtils {
    private val isoCodes: Set<String> by lazy { Locale.getISOCountries().toSet() }

    private val nameToCode: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            val name = Locale("", code).getDisplayCountry(Locale.ENGLISH)
            if (name.isNotBlank() && name != code) map[normalize(name)] = code
        }
        mapOf(
            "usa" to "US", "united states of america" to "US", "america" to "US",
            "uk" to "GB", "england" to "GB", "britain" to "GB", "great britain" to "GB",
            "uae" to "AE", "emirates" to "AE", "hong kong" to "HK", "macau" to "MO",
            "russia" to "RU", "south korea" to "KR", "korea" to "KR", "turkey" to "TR",
            "turkiye" to "TR", "t rkiye" to "TR", "czech republic" to "CZ", "czechia" to "CZ",
            "vietnam" to "VN", "taiwan" to "TW", "holland" to "NL", "ivory coast" to "CI",
            "swaziland" to "SZ", "palestine" to "PS", "iran" to "IR", "syria" to "SY"
        ).forEach { (k, v) -> map[k] = v }
        map
    }

    /** Country names of at least 4 letters, longest first, for contained-name matching. */
    private val searchableNames: List<Pair<String, String>> by lazy {
        nameToCode.entries.filter { it.key.length >= 4 }.map { it.key to it.value }.sortedByDescending { it.first.length }
    }

    private val blockedTlds = setOf(
        "io", "co", "me", "tv", "cc", "ws", "ai", "fm", "ly", "to", "gg", "nu", "sh", "ac",
        "tk", "ml", "ga", "cf", "gq", "vc", "la", "im", "ms", "su", "so", "sc", "ag", "pw"
    )

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}]+"), " ").trim()

    fun isValid(code: String?): Boolean = code != null && code.length == 2 && code in isoCodes

    fun flag(code: String?): String {
        if (!isValid(code)) return "\uD83C\uDF10"
        val up = code!!.uppercase(Locale.ROOT)
        return buildString {
            appendCodePoint(0x1F1E6 + (up[0] - 'A'))
            appendCodePoint(0x1F1E6 + (up[1] - 'A'))
        }
    }

    fun displayName(code: String?): String =
        if (isValid(code)) Locale("", code!!).getDisplayCountry(Locale.ENGLISH) else "Unknown"

    private fun fromFlagEmoji(text: String): String? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i + n < text.length) {
                val cp2 = text.codePointAt(i + n)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val code = "" + ('A' + (cp - 0x1F1E6)) + ('A' + (cp2 - 0x1F1E6))
                    if (code in isoCodes) return code
                }
            }
            i += n
        }
        return null
    }

    /** Detects a flag emoji or an English country name inside [text]. */
    fun detectFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        fromFlagEmoji(text)?.let { return it }
        val norm = normalize(text)
        if (norm.isEmpty()) return null
        nameToCode[norm]?.let { return it }
        val padded = " $norm "
        for ((name, code) in searchableNames) if (padded.contains(" $name ")) return code
        return null
    }

    /** A feed label such as "Australia", "🇨🇦 Canada" or just "AU". */
    fun detectFromLabel(label: String?): String? {
        detectFromText(label)?.let { return it }
        val t = label?.trim().orEmpty()
        return if (t.length == 2 && t == t.uppercase(Locale.ROOT) && t in isoCodes) t else null
    }

    /** Server names such as "vless-au_y94bk by ipspeed.info", "[DE] Frankfurt" or "Germany-02". */
    fun detectFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val cleaned = name.replace(Regex("\\s+by\\s+\\S+$", RegexOption.IGNORE_CASE), "").trim()
        detectFromText(cleaned)?.let { return it }
        Regex("vless[-_ ]([A-Za-z]{2})(?![A-Za-z])", RegexOption.IGNORE_CASE).find(cleaned)?.let {
            val code = it.groupValues[1].uppercase(Locale.ROOT)
            if (code in isoCodes) return code
        }
        for (m in Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])").findAll(cleaned)) {
            val code = m.groupValues[1]
            if (code in isoCodes) return code
        }
        return null
    }

    /** Country-code top-level domains such as ".de"; generic-use ccTLDs are ignored. */
    fun detectFromHost(host: String): String? {
        val tld = host.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (tld.length != 2 || tld in blockedTlds) return null
        val code = tld.uppercase(Locale.ROOT)
        return if (code == "UK") "GB" else code.takeIf { it in isoCodes }
    }
}
