package dev.nytweetdeck.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.EmbeddedPost
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.Post
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class PostShareOutcome { SHARED, COPIED, INVALID }

internal fun postShareUrl(postId: String): String? = postId
    .takeIf { it.matches(Regex("[0-9]{1,30}")) }
    ?.let { "https://x.com/i/status/$it" }

internal fun sharePostOrCopy(
    context: Context,
    postId: String,
    chooserTitle: String,
): PostShareOutcome {
    val url = postShareUrl(postId) ?: return PostShareOutcome.INVALID
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    val chooser = Intent.createChooser(sendIntent, chooserTitle)
    val shared = runCatching {
        if (chooser.resolveActivity(context.packageManager) == null) return@runCatching false
        context.startActivity(chooser)
        true
    }.getOrDefault(false)
    if (shared) return PostShareOutcome.SHARED

    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(chooserTitle, url))
    return PostShareOutcome.COPIED
}

internal fun copyShareText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun shareAuthorLabel(author: Author): String {
    val display = author.displayName.trim()
    val handle = author.username.trim().removePrefix("@")
    val fallback = author.id.trim()
    val name = display.ifBlank { handle.ifBlank { fallback } }
    return "$name@${handle.ifBlank { fallback }}"
}

internal fun directShareMediaLinks(media: List<Media>): List<String> {
    val links = mutableListOf<String>()
    for (item in media) {
        val url = (item.url ?: item.previewUrl)?.trim().orEmpty()
        if (url.isNotEmpty() && url !in links) {
            links.add(url)
        }
    }
    return links
}

private val SHARE_ABSOLUTE_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

internal fun formatShareAbsoluteTime(
    createdAt: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? {
    val value = createdAt?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching {
            ZonedDateTime.parse(
                value,
                DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH),
            ).toInstant()
        }.getOrNull()
        ?: return null
    return instant.atZone(zoneId).format(SHARE_ABSOLUTE_FORMAT)
}

internal fun formatDetailedShareText(
    authorLabel: String,
    body: String,
    mediaLinks: List<String>,
    absoluteTime: String?,
    relativeTime: String?,
    quotedAuthorLabel: String?,
    quotedBody: String?,
    postUrl: String,
): String {
    val lines = mutableListOf(authorLabel)
    if (body.trim().isNotEmpty()) {
        lines.add(body.trim())
    }
    lines.addAll(mediaLinks)
    val timeLine = when {
        !absoluteTime.isNullOrBlank() && !relativeTime.isNullOrBlank() ->
            "$absoluteTime ($relativeTime)"
        !absoluteTime.isNullOrBlank() -> absoluteTime
        !relativeTime.isNullOrBlank() -> relativeTime
        else -> null
    }
    if (timeLine != null) {
        lines.add(timeLine)
    }
    if (quotedAuthorLabel != null) {
        lines.add(quotedAuthorLabel)
        if (!quotedBody.isNullOrBlank()) {
            quotedBody.trim().split("\n").forEach { line ->
                lines.add("> ${line.trimEnd()}")
            }
        }
    }
    lines.add(postUrl)
    return lines.joinToString("\n")
}

/** X公式のGrokプリ翻訳をそのまま使う。訳文がない場合は原文へ戻す。 */
internal fun translatedShareBody(
    text: String,
    preTranslated: dev.nytweetdeck.android.model.Translation?,
): String {
    val translated = preTranslated?.text?.trim().orEmpty()
    return translated.ifBlank { text.trim() }
}

/**
 * 詳細コピー形式を作る。元ポストのメディアだけを含め、引用先のメディアとURLは含めない。
 * 引用ポストの本文は大なり記号で引用し、末尾は元ポストのURLだけにする。
 * translatedが真の場合は本文と引用本文をGrokプリ翻訳へ置き換える。
 */
internal fun formatDetailedPostShare(
    post: Post,
    absoluteTime: String?,
    relativeTime: String?,
    translated: Boolean = false,
): String? {
    val url = postShareUrl(post.id) ?: return null
    return formatDetailedPostShare(post, post.quotedPost, absoluteTime, relativeTime, url, translated)
}

internal fun formatDetailedPostShare(
    post: Post,
    quotedPost: EmbeddedPost?,
    absoluteTime: String?,
    relativeTime: String?,
    postUrl: String,
    translated: Boolean = false,
): String = formatDetailedShareText(
    authorLabel = shareAuthorLabel(post.author),
    body = if (translated) translatedShareBody(post.text, post.preTranslated) else post.text,
    mediaLinks = directShareMediaLinks(post.media),
    absoluteTime = absoluteTime,
    relativeTime = relativeTime,
    quotedAuthorLabel = quotedPost?.let { shareAuthorLabel(it.author) },
    quotedBody = quotedPost?.let {
        if (translated) translatedShareBody(it.text, it.preTranslated) else it.text
    },
    postUrl = postUrl,
)
