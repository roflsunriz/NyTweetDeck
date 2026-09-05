package dev.nytweetdeck.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.CommunityNote
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationCandidate
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.text.hasTranslatableText

@Composable
internal fun TranslatableCommunityNote(
    note: CommunityNote,
    states: Map<String, PostTranslationUiState>,
    autoTranslate: Boolean,
    onNeeded: (TranslationCandidate) -> Unit,
    onRetry: (TranslationCandidate) -> Unit,
    onToggle: (String) -> Unit,
    onOpenSource: ((Intent) -> Unit)? = null,
) {
    val original = note.text.orEmpty()
    val key = "note:${note.noteId}"
    val candidate = TranslationCandidate(key, note.language, null, original, note)
    val hasText = hasTranslatableText(original) && note.noteId != null
    val state = states[key]?.takeIf { hasText && autoTranslate }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(key, original, autoTranslate, state?.status) {
        if (hasText && autoTranslate && state == null) onNeeded(candidate)
    }
    val translation = state?.translation?.takeIf { state.status == TranslationLoadStatus.READY && !state.showOriginal }
    Column(Modifier.padding(top = 8.dp).testTag("community-note-$key")) {
        note.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        CommunityNoteText(translation?.text ?: original, translation?.sources ?: note.sources) { intent ->
            if (onOpenSource != null) onOpenSource(intent) else intent.dataString?.let(uriHandler::openUri)
        }
        when (state?.status) {
            TranslationLoadStatus.LOADING -> Text(stringResource(R.string.translation_loading), style = MaterialTheme.typography.labelSmall)
            TranslationLoadStatus.FAILED -> {
                Text(stringResource(if (state.unavailable) R.string.translation_unavailable else R.string.translation_failed),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onRetry(candidate) }, modifier = Modifier.testTag("note-translation-retry")) {
                    Text(stringResource(R.string.translation_retry))
                }
            }
            TranslationLoadStatus.READY -> TextButton(onClick = { onToggle(key) }, modifier = Modifier.testTag("note-translation-toggle")) {
                Text(stringResource(if (state.showOriginal) R.string.translation_show_translation else R.string.translation_show_original))
            }
            else -> Unit
        }
        note.footer?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}
