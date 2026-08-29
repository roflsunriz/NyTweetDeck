package dev.nytweetdeck.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.model.PostDetailUiState
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.model.TranslationCandidate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
internal fun PostDetailDialog(
    state: PostDetailUiState,
    replySort: RankingMode,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onReplySortChange: (RankingMode) -> Unit,
    onToggleDeemphasized: () -> Unit,
    onPostClick: (String) -> Unit,
    onQuoteClick: (String) -> Unit,
    onAuthorClick: ((Author) -> Unit)? = null,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onImpressionClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    onArticleClick: (String, Article) -> Unit = { _, _ -> },
    onPostMenuClick: (dev.nytweetdeck.android.model.Post) -> Unit = {},
    translationStates: Map<String, PostTranslationUiState> = emptyMap(),
    autoTranslatePosts: Boolean = true,
    onTranslationNeeded: (TranslationCandidate) -> Unit = {},
    onTranslationRetry: (TranslationCandidate) -> Unit = {},
    onToggleOriginal: (String) -> Unit = {},
    mediaPreview: Boolean = true,
    videoAutoplay: Boolean = false,
    videoLoop: Boolean = true,
    videoVolume: Int = 100,
) {
    if (state.status == PostDetailStatus.CLOSED) return
    val detailScrollPositions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .testTag("post-detail"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                DetailHeader(onDismiss)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                when (state.status) {
                    PostDetailStatus.LOADING -> DetailLoading()
                    PostDetailStatus.FAILED -> DetailFailed(onRetry)
                    PostDetailStatus.READY -> key(state.postId) { DetailReady(
                        state = state,
                        savedScrollPositions = detailScrollPositions,
                        replySort = replySort,
                        onLoadMore = onLoadMore,
                        onReplySortChange = onReplySortChange,
                        onToggleDeemphasized = onToggleDeemphasized,
                        onPostClick = onPostClick,
                        onQuoteClick = onQuoteClick,
                        onAuthorClick = onAuthorClick,
                        onReplyClick = onReplyClick,
                        onRepostClick = onRepostClick,
                        onLikeClick = onLikeClick,
                        onImpressionClick = onImpressionClick,
                        onBookmarkClick = onBookmarkClick,
                        onShareClick = onShareClick,
                        onDownloadClick = onDownloadClick,
                        onArticleClick = onArticleClick,
                        onPostMenuClick = onPostMenuClick,
                        translationStates = translationStates,
                        autoTranslatePosts = autoTranslatePosts,
                        onTranslationNeeded = onTranslationNeeded,
                        onTranslationRetry = onTranslationRetry,
                        onToggleOriginal = onToggleOriginal,
                        onRetry = onRetry,
                        mediaPreview = mediaPreview,
                        videoAutoplay = videoAutoplay,
                        videoLoop = videoLoop,
                        videoVolume = videoVolume,
                    ) }
                    PostDetailStatus.CLOSED -> Unit
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.post_detail_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("close-post-detail"),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close_post_detail),
            )
        }
    }
}

@Composable
private fun DetailLoading() {
    Box(
        modifier = Modifier.fillMaxSize().testTag("post-detail-loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DetailFailed(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.post_detail_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag("post-detail-retry")) {
            Text(stringResource(R.string.post_detail_retry))
        }
    }
}

@Composable
private fun DetailReady(
    state: PostDetailUiState,
    savedScrollPositions: MutableMap<String, Pair<Int, Int>>,
    replySort: RankingMode,
    onLoadMore: () -> Unit,
    onReplySortChange: (RankingMode) -> Unit,
    onToggleDeemphasized: () -> Unit,
    onPostClick: (String) -> Unit,
    onQuoteClick: (String) -> Unit,
    onAuthorClick: ((Author) -> Unit)?,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onImpressionClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    onArticleClick: (String, Article) -> Unit,
    onPostMenuClick: (dev.nytweetdeck.android.model.Post) -> Unit,
    translationStates: Map<String, PostTranslationUiState>,
    autoTranslatePosts: Boolean,
    onTranslationNeeded: (TranslationCandidate) -> Unit,
    onTranslationRetry: (TranslationCandidate) -> Unit,
    onToggleOriginal: (String) -> Unit,
    onRetry: () -> Unit,
    mediaPreview: Boolean,
    videoAutoplay: Boolean,
    videoLoop: Boolean,
    videoVolume: Int,
) {
    val page = state.page
    if (page == null) {
        DetailFailed(onRetry)
        return
    }
    val savedScroll = savedScrollPositions[page.post.id]
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScroll?.first ?: 0,
        initialFirstVisibleItemScrollOffset = savedScroll?.second ?: 0,
    )
    LaunchedEffect(listState, page.post.id) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { savedScrollPositions[page.post.id] = it }
    }
    LaunchedEffect(listState, page.nextCursor, state.isLoadingMore) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it && page.nextCursor != null && !state.isLoadingMore }
            .collect { onLoadMore() }
    }
    val deemphasizedCount = page.replies.count { it.quality.isDeemphasized }
    val firstDeemphasizedReplyId = page.replies
        .firstOrNull { it.quality.isDeemphasized }
        ?.post
        ?.id
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        item(key = "detail-post") {
            PostCard(
                post = page.post,
                onPostClick = onPostClick,
                onQuoteClick = onQuoteClick,
                onAuthorClick = onAuthorClick,
                onReplyClick = onReplyClick,
                onRepostClick = onRepostClick,
                onLikeClick = onLikeClick,
                onImpressionClick = onImpressionClick,
                onBookmarkClick = onBookmarkClick,
                onShareClick = onShareClick,
                onDownloadClick = onDownloadClick,
                onArticleClick = onArticleClick,
                onMenuClick = onPostMenuClick,
                translationStates = translationStates,
                autoTranslatePosts = autoTranslatePosts,
                onTranslationNeeded = onTranslationNeeded,
                onTranslationRetry = onTranslationRetry,
                onToggleOriginal = onToggleOriginal,
                mediaPreview = mediaPreview,
                videoAutoplay = videoAutoplay,
                videoLoop = videoLoop,
                videoVolume = videoVolume,
            )
        }
        item(key = "reply-sort") {
            ReplySortControls(
                selected = replySort,
                onReplySortChange = onReplySortChange,
            )
        }
        if (page.replies.isEmpty()) {
            item(key = "empty-replies") {
                Text(
                    text = stringResource(R.string.replies_empty),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            page.replies.forEach { reply ->
                if (reply.post.id == firstDeemphasizedReplyId) {
                    item(key = "deemphasized-toggle") {
                        DeemphasizedRepliesToggle(
                            count = deemphasizedCount,
                            expanded = state.showDeemphasizedReplies,
                            onToggle = onToggleDeemphasized,
                        )
                    }
                }
                if (!reply.quality.isDeemphasized || state.showDeemphasizedReplies) {
                    item(key = reply.post.id) {
                        PostCard(
                            post = reply.post,
                            onPostClick = onPostClick,
                            onQuoteClick = onQuoteClick,
                            onAuthorClick = onAuthorClick,
                            onReplyClick = onReplyClick,
                            onRepostClick = onRepostClick,
                            onLikeClick = onLikeClick,
                            onImpressionClick = onImpressionClick,
                            onBookmarkClick = onBookmarkClick,
                            onShareClick = onShareClick,
                            onDownloadClick = onDownloadClick,
                            onArticleClick = onArticleClick,
                            onMenuClick = onPostMenuClick,
                            translationStates = translationStates,
                            autoTranslatePosts = autoTranslatePosts,
                            onTranslationNeeded = onTranslationNeeded,
                            onTranslationRetry = onTranslationRetry,
                            onToggleOriginal = onToggleOriginal,
                            mediaPreview = mediaPreview,
                            videoAutoplay = videoAutoplay,
                            videoLoop = videoLoop,
                            videoVolume = videoVolume,
                        )
                    }
                }
            }
        }
        DetailLoadMoreFooter(
            nextCursor = page.nextCursor,
            isLoadingMore = state.isLoadingMore,
            loadMoreFailed = state.loadMoreFailed,
            onLoadMore = onLoadMore,
        )
    }
}

@Composable
private fun ReplySortControls(
    selected: RankingMode,
    onReplySortChange: (RankingMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReplySortChip(
            mode = RankingMode.RELEVANCE,
            selected = selected,
            label = stringResource(R.string.reply_sort_relevance),
            onReplySortChange = onReplySortChange,
        )
        ReplySortChip(
            mode = RankingMode.RECENCY,
            selected = selected,
            label = stringResource(R.string.reply_sort_recency),
            onReplySortChange = onReplySortChange,
        )
        ReplySortChip(
            mode = RankingMode.LIKES,
            selected = selected,
            label = stringResource(R.string.reply_sort_likes),
            onReplySortChange = onReplySortChange,
        )
    }
}

@Composable
private fun ReplySortChip(
    mode: RankingMode,
    selected: RankingMode,
    label: String,
    onReplySortChange: (RankingMode) -> Unit,
) {
    FilterChip(
        selected = mode == selected,
        onClick = { onReplySortChange(mode) },
        label = { Text(label) },
        modifier = Modifier.testTag("reply-sort-" + mode.name.lowercase()),
    )
}

@Composable
private fun DeemphasizedRepliesToggle(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("deemphasized-replies"),
    ) {
        Text(
            text = stringResource(
                if (expanded) {
                    R.string.deemphasized_replies_expanded
                } else {
                    R.string.deemphasized_replies_collapsed
                },
                count,
            ),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.DetailLoadMoreFooter(
    nextCursor: String?,
    isLoadingMore: Boolean,
    loadMoreFailed: Boolean,
    onLoadMore: () -> Unit,
) {
    when {
        isLoadingMore -> item(key = "loading-more-replies") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("load-more-replies"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
        loadMoreFailed -> item(key = "load-more-replies-failed") {
            TextButton(
                onClick = onLoadMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("load-more-replies"),
            ) {
                Text(stringResource(R.string.retry_load_more_replies))
            }
        }
        nextCursor != null -> item(key = "load-more-replies") {
            TextButton(
                onClick = onLoadMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("load-more-replies"),
            ) {
                Text(stringResource(R.string.load_more_replies))
            }
        }
    }
}
