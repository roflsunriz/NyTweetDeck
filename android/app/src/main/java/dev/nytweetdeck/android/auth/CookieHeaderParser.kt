package dev.nytweetdeck.android.auth

internal object CookieHeaderParser {
    fun parse(header: String): Map<String, String> = header
        .split(';')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            name.takeIf { it.isNotEmpty() }?.let { it to value }
        }
        .toMap()
}
