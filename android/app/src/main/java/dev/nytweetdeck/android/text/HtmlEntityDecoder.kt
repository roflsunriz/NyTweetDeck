package dev.nytweetdeck.android.text

internal object HtmlEntityDecoder {
    fun decode(value: String): String {
        if ('&' !in value) return value
        return buildString(value.length) {
            var copiedUntil = 0
            ENTITY.findAll(value).forEach { match ->
                val replacement = replacement(
                    match.groups[1]?.value,
                    match.groups[2]?.value,
                    match.groups[3]?.value,
                ) ?: return@forEach
                append(value, copiedUntil, match.range.first)
                append(replacement)
                copiedUntil = match.range.last + 1
            }
            if (copiedUntil == 0) return value
            append(value, copiedUntil, value.length)
        }
    }

    private fun replacement(decimal: String?, hexadecimal: String?, named: String?): String? {
        if (named != null) {
            return when (named) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> "\u00a0"
                else -> null
            }
        }
        val codePoint = (decimal ?: hexadecimal)
            ?.toIntOrNull(if (decimal != null) 10 else 16)
            ?: return null
        if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT || codePoint in SURROGATE_RANGE) {
            return null
        }
        return String(Character.toChars(codePoint))
    }

    private val ENTITY = Regex(
        "&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|([A-Za-z][A-Za-z0-9]{1,31}));",
    )
    private val SURROGATE_RANGE = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
}
