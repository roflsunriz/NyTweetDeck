package dev.nytweetdeck.android.security

import java.net.URI
import java.util.Locale

internal fun verifiedExternalHttpsUrl(
    value: String,
    allowedHostSuffixes: Set<String>? = null,
): String? = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
    val allowed = allowedHostSuffixes == null || allowedHostSuffixes.any { suffix ->
        val normalized = suffix.lowercase(Locale.ROOT)
        host == normalized || host.endsWith(".$normalized")
    }
    uri.takeIf {
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            allowed
    }?.toASCIIString()
}.getOrNull()
