package dev.nytweetdeck.android.auth

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class XWebSessionCookies(
    val authToken: String,
    val csrfToken: String,
    val userId: String,
) {
    companion object {
        fun fromHeader(header: String): XWebSessionCookies? {
            val cookies = CookieHeaderParser.parse(header)
            val authToken = cookies["auth_token"]?.takeIf(String::isNotBlank) ?: return null
            val csrfToken = cookies["ct0"]?.takeIf(String::isNotBlank) ?: return null
            val userId = parseUserId(cookies["twid"] ?: return null) ?: return null
            return XWebSessionCookies(authToken, csrfToken, userId)
        }

        internal fun parseUserId(value: String): String? {
            val decoded = runCatching {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrNull() ?: return null
            val id = decoded.substringAfter('=', decoded)
            return id.takeIf { it.matches(Regex("[0-9]{1,24}")) }
        }
    }
}
