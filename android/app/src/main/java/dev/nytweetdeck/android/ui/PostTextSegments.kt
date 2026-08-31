package dev.nytweetdeck.android.ui

import java.net.URI

internal sealed interface PostTextSegment {
    val text: String

    data class Plain(override val text: String) : PostTextSegment

    data class Hashtag(override val text: String) : PostTextSegment

    data class Url(override val text: String, val url: String) : PostTextSegment
}

internal fun postTextSegments(value: String): List<PostTextSegment> = buildList {
    var cursor = 0
    POST_TOKEN_PATTERN.findAll(value).forEach { match ->
        if (match.range.first > cursor) appendPlain(value.substring(cursor, match.range.first))
        val token = match.value
        if (token.startsWith("#")) {
            add(PostTextSegment.Hashtag(token))
        } else {
            val link = token.trimUrlEnd()
            if (link.isSafeHttpUrl()) {
                add(PostTextSegment.Url(link, link))
                appendPlain(token.substring(link.length))
            } else {
                appendPlain(token)
            }
        }
        cursor = match.range.last + 1
    }
    appendPlain(value.substring(cursor))
}

private fun MutableList<PostTextSegment>.appendPlain(value: String) {
    if (value.isEmpty()) return
    val previous = lastOrNull()
    if (previous is PostTextSegment.Plain) {
        this[lastIndex] = PostTextSegment.Plain(previous.text + value)
    } else {
        add(PostTextSegment.Plain(value))
    }
}

private fun String.trimUrlEnd(): String {
    var end = length
    while (end > 0) {
        val final = this[end - 1]
        if (final in TRAILING_PUNCTUATION) {
            end -= 1
            continue
        }
        val candidate = substring(0, end)
        val unmatchedClosing = BRACKETS.any { (opening, closing) ->
            final == closing && candidate.count { it == closing } > candidate.count { it == opening }
        }
        if (unmatchedClosing) {
            end -= 1
            continue
        }
        break
    }
    return substring(0, end)
}

private fun String.isSafeHttpUrl(): Boolean = runCatching {
    val parsed = URI(this)
    (parsed.scheme.equals("http", ignoreCase = true) ||
        parsed.scheme.equals("https", ignoreCase = true)) && !parsed.host.isNullOrBlank()
}.getOrDefault(false)

private val POST_TOKEN_PATTERN = Regex(
    """https?://[^\s<>"'。、！？]+|#[\p{L}\p{N}_]+""",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_PUNCTUATION = setOf('.', ',', '!', '?', ':', ';', '。', '、', '！', '？')
private val BRACKETS = listOf('(' to ')', '[' to ']', '{' to '}')
