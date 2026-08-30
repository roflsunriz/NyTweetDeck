package dev.nytweetdeck.android.xapi

import java.net.URI

internal enum class PostUrlEntityKind {
    LINK,
    MEDIA,
    ARTICLE,
}

internal data class PostUrlEntity(
    val shortUrl: String,
    val expandedUrl: String?,
    val unwoundUrl: String?,
    val kind: PostUrlEntityKind,
)

internal object PostTextUrlNormalizer {
    fun normalize(value: String, entities: List<PostUrlEntity>): String {
        if (entities.isEmpty()) return value
        val replacements = linkedMapOf<String, String>()
        entities.forEach { entity ->
            if (!entity.shortUrl.isTcoUrl()) return@forEach
            if (entity.kind != PostUrlEntityKind.LINK) {
                replacements[entity.shortUrl] = ""
                return@forEach
            }
            val destination = sequenceOf(entity.unwoundUrl, entity.expandedUrl)
                .filterNotNull()
                .firstOrNull { it.isHttpUrl() }
            if (destination != null && !destination.isTcoUrl()) {
                replacements.putIfAbsent(entity.shortUrl, destination)
            }
        }
        var normalized = value
        var removedContent = false
        replacements.forEach { (shortUrl, replacement) ->
            normalized = normalized.replace(shortUrl, replacement)
            removedContent = removedContent || replacement.isEmpty()
        }
        return if (removedContent) {
            normalized
                .replace(EXCESSIVE_HORIZONTAL_SPACE, " ")
                .replace(TRAILING_HORIZONTAL_SPACE, "")
                .replace(LEADING_HORIZONTAL_SPACE, "")
                .replace(EXCESSIVE_LINE_BREAKS, "\n\n")
                .trim()
        } else {
            normalized
        }
    }

    private fun String.isTcoUrl(): Boolean = isHttpUrl() &&
        runCatching { URI(this).host.equals("t.co", ignoreCase = true) }.getOrDefault(false)

    private fun String.isHttpUrl(): Boolean = runCatching {
        val uri = URI(this)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            uri.host != null
    }.getOrDefault(false)

    private val EXCESSIVE_HORIZONTAL_SPACE = Regex("[ \\t]{2,}")
    private val TRAILING_HORIZONTAL_SPACE = Regex("[ \\t]+(?=\\R|$)")
    private val LEADING_HORIZONTAL_SPACE = Regex("(?m)^[ \\t]+")
    private val EXCESSIVE_LINE_BREAKS = Regex("\\R{3,}")
}
