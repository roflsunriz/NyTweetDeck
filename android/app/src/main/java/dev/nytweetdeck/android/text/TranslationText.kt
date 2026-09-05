package dev.nytweetdeck.android.text

/** Identifiers, media URLs, numbers and emoji alone have no text for X to translate. */
fun hasTranslatableText(text: String): Boolean {
    val prose = MENTION.replace(URL.replace(text, ""), "")
    return prose.codePoints().anyMatch(Character::isLetter)
}

private val URL = Regex("https?://[^\\s\\p{Z}\\uFEFF]+", RegexOption.IGNORE_CASE)
private val MENTION = Regex("(?<![\\p{L}\\p{N}_])[@＠][A-Za-z0-9_]{1,15}(?![A-Za-z0-9_])")
