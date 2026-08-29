package dev.nytweetdeck.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.CommunityNoteSource
import dev.nytweetdeck.android.model.CommunityNoteStatus
import dev.nytweetdeck.android.model.CommunityNoteUiState
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationCandidate
import java.util.Locale

private const val COMMUNITY_NOTE_SOURCE_TAG = "community-note-source-url"

@Composable
internal fun CommunityNoteDialog(
    state: CommunityNoteUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenSource: (Intent) -> Unit,
    onPostClick: (String) -> Unit = {},
    onQuoteClick: (String) -> Unit = {},
    onReplyClick: (String) -> Unit = {},
    onRepostClick: (String) -> Unit = {},
    onLikeClick: (String) -> Unit = {},
    onImpressionClick: (String) -> Unit = {},
    onBookmarkClick: (String) -> Unit = {},
    onShareClick: (String) -> Unit = {},
    onDownloadClick: (String) -> Unit = {},
    onArticleClick: (String, Article) -> Unit = { _, _ -> },
    translationStates: Map<String, PostTranslationUiState> = emptyMap(),
    autoTranslatePosts: Boolean = true,
    onTranslationNeeded: (TranslationCandidate) -> Unit = {},
    onTranslationRetry: (TranslationCandidate) -> Unit = {},
    onToggleOriginal: (String) -> Unit = {},
) {
    if (state.status == CommunityNoteStatus.CLOSED) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .testTag("community-note"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                when (state.status) {
                    CommunityNoteStatus.LOADING -> CommunityNoteLoading()
                    CommunityNoteStatus.FAILED -> CommunityNoteFailed(onRetry)
                    CommunityNoteStatus.READY -> CommunityNoteReady(
                        state = state,
                        onRetry = onRetry,
                        onOpenSource = onOpenSource,
                        onPostClick = onPostClick,
                        onQuoteClick = onQuoteClick,
                        onReplyClick = onReplyClick,
                        onRepostClick = onRepostClick,
                        onLikeClick = onLikeClick,
                        onImpressionClick = onImpressionClick,
                        onBookmarkClick = onBookmarkClick,
                        onShareClick = onShareClick,
                        onDownloadClick = onDownloadClick,
                        onArticleClick = onArticleClick,
                        translationStates = translationStates,
                        autoTranslatePosts = autoTranslatePosts,
                        onTranslationNeeded = onTranslationNeeded,
                        onTranslationRetry = onTranslationRetry,
                        onToggleOriginal = onToggleOriginal,
                    )
                    CommunityNoteStatus.CLOSED -> Unit
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .testTag("community-note-close"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.community_note_close),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityNoteLoading() {
    Box(
        modifier = Modifier.fillMaxSize().testTag("community-note-loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CommunityNoteFailed(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.community_note_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag("community-note-retry")) {
            Text(stringResource(R.string.community_note_retry))
        }
    }
}

@Composable
private fun CommunityNoteReady(
    state: CommunityNoteUiState,
    onRetry: () -> Unit,
    onOpenSource: (Intent) -> Unit,
    onPostClick: (String) -> Unit,
    onQuoteClick: (String) -> Unit,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onImpressionClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    onArticleClick: (String, Article) -> Unit,
    translationStates: Map<String, PostTranslationUiState>,
    autoTranslatePosts: Boolean,
    onTranslationNeeded: (TranslationCandidate) -> Unit,
    onTranslationRetry: (TranslationCandidate) -> Unit,
    onToggleOriginal: (String) -> Unit,
) {
    val page = state.page
    if (page == null) {
        CommunityNoteFailed(onRetry)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PostCard(
            post = page.post,
            onPostClick = onPostClick,
            onQuoteClick = onQuoteClick,
            onReplyClick = onReplyClick,
            onRepostClick = onRepostClick,
            onLikeClick = onLikeClick,
            onImpressionClick = onImpressionClick,
            onBookmarkClick = onBookmarkClick,
            onShareClick = onShareClick,
            onDownloadClick = onDownloadClick,
            onArticleClick = onArticleClick,
            translationStates = translationStates,
            autoTranslatePosts = autoTranslatePosts,
            onTranslationNeeded = onTranslationNeeded,
            onTranslationRetry = onTranslationRetry,
            onToggleOriginal = onToggleOriginal,
        )
        Text(
            text = stringResource(R.string.community_note_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        CommunityNoteText(
            text = page.detail.text,
            sources = page.detail.sources,
            onOpenSource = onOpenSource,
        )
    }
}

@Composable
private fun CommunityNoteText(
    text: String,
    sources: List<CommunityNoteSource>,
    onOpenSource: (Intent) -> Unit,
) {
    val annotated = remember(text, sources) { annotatedCommunityNote(text, sources) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(annotated) {
                detectTapGestures { position ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(position)
                    annotated.getStringAnnotations(
                        tag = COMMUNITY_NOTE_SOURCE_TAG,
                        start = offset.coerceAtMost((annotated.length - 1).coerceAtLeast(0)),
                        end = (offset + 1).coerceAtMost(annotated.length),
                    ).firstOrNull()?.item?.let { sourceUrl ->
                        safeSourceIntent(sourceUrl)?.let(onOpenSource)
                    }
                }
            }
            .testTag("community-note-source"),
        style = MaterialTheme.typography.bodyLarge,
        onTextLayout = { layoutResult = it },
    )
}

private fun annotatedCommunityNote(
    text: String,
    sources: List<CommunityNoteSource>,
): AnnotatedString = buildAnnotatedString {
    var copiedUntil = 0
    sources.sortedBy(CommunityNoteSource::fromIndex).forEach { source ->
        if (safeSourceIntent(source.url) == null) return@forEach
        val start = source.fromIndex.coerceIn(0, text.length)
        val end = source.toIndex.coerceIn(0, text.length)
        if (start < copiedUntil || end <= start) return@forEach
        append(text.substring(copiedUntil, start))
        val annotationStart = length
        withStyle(
            SpanStyle(
                color = androidx.compose.ui.graphics.Color(0xFF1D9BF0),
                textDecoration = TextDecoration.Underline,
            ),
        ) {
            append(text.substring(start, end))
        }
        addStringAnnotation(
            tag = COMMUNITY_NOTE_SOURCE_TAG,
            annotation = source.url,
            start = annotationStart,
            end = length,
        )
        copiedUntil = end
    }
    append(text.substring(copiedUntil))
}

private fun safeSourceIntent(value: String): Intent? = runCatching {
    val uri = Uri.parse(value)
    if (uri.scheme.equals("https", ignoreCase = true)) {
        Intent(Intent.ACTION_VIEW, uri)
    } else {
        null
    }
}.getOrNull()
