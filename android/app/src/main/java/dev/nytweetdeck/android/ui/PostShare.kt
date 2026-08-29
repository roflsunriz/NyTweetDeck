package dev.nytweetdeck.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

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
