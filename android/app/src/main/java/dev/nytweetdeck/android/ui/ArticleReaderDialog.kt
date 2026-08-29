package dev.nytweetdeck.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.ArticleReaderStatus
import dev.nytweetdeck.android.model.ArticleReaderUiState
import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl

@Composable
internal fun ArticleReaderDialog(
    state: ArticleReaderUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenX: (Intent) -> Unit,
) {
    if (state.status == ArticleReaderStatus.CLOSED) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .testTag("article-reader"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                when (state.status) {
                    ArticleReaderStatus.LOADING -> ArticleReaderLoading()
                    ArticleReaderStatus.FAILED -> ArticleReaderFailed(onRetry)
                    ArticleReaderStatus.READY -> ArticleReaderReady(
                        article = state.article,
                        onRetry = onRetry,
                        onOpenX = onOpenX,
                    )
                    ArticleReaderStatus.CLOSED -> Unit
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .testTag("article-reader-close"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.article_reader_close),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleReaderLoading() {
    Box(
        modifier = Modifier.fillMaxSize().testTag("article-reader-loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ArticleReaderFailed(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.article_reader_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag("article-reader-retry")) {
            Text(stringResource(R.string.article_reader_retry))
        }
    }
}

@Composable
private fun ArticleReaderReady(
    article: Article?,
    onRetry: () -> Unit,
    onOpenX: (Intent) -> Unit,
) {
    if (article == null) {
        ArticleReaderFailed(onRetry)
        return
    }
    val viewIntent = remember(article.url) { safeXViewIntent(article.url) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        article.coverImageUrl?.let { coverUrl ->
            AsyncImage(
                model = safeImageUrl(coverUrl),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            text = article.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = article.body?.takeIf(String::isNotBlank)
                ?: article.previewText?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.article_reader_body_unavailable),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = {
                viewIntent?.let(onOpenX)
            },
            enabled = viewIntent != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("article-reader-open-x"),
        ) {
            Text(stringResource(R.string.article_reader_open_x))
        }
    }
}

private fun safeXViewIntent(value: String): Intent? = runCatching {
    val verified = verifiedExternalHttpsUrl(value, setOf("x.com")) ?: return@runCatching null
    Intent(Intent.ACTION_VIEW, Uri.parse(verified)).addCategory(Intent.CATEGORY_BROWSABLE)
}.getOrNull()
