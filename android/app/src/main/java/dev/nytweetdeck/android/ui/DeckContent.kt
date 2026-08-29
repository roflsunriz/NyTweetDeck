package dev.nytweetdeck.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.ColumnScrollPosition
import dev.nytweetdeck.android.model.ColumnTimelineState
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.DirectMessageColumnState
import dev.nytweetdeck.android.model.NotificationColumnState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.model.TrendColumnState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeckContent(
    state: DeckUiState,
    columnScrollPositions: Map<String, ColumnScrollPosition>,
    onRemoveColumn: (String) -> Unit,
    onAddColumn: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRefreshColumn: (String) -> Unit,
    onLoadMoreColumn: (String) -> Unit,
    onClearNewPostsColumn: (String) -> Unit,
    onVisibleColumnsChanged: (Set<String>) -> Unit,
    onMoveColumn: (String, Int) -> Unit,
    onSaveColumnScrollPosition: (String, Int, Int, String?) -> Unit,
    onPostClick: (String) -> Unit = {},
    onQuoteClick: (String) -> Unit = {},
    onReplyClick: (String) -> Unit = {},
    onRepostClick: (String) -> Unit = {},
    onLikeClick: (String) -> Unit = {},
    onImpressionClick: (String) -> Unit = {},
    onBookmarkClick: (String) -> Unit = {},
    onShareClick: (String) -> Unit = {},
    onDownloadClick: (String) -> Unit = {},
) {
    val latestVisibleColumnsChanged by rememberUpdatedState(onVisibleColumnsChanged)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (state.isInitializing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).testTag("deck-initializing"),
            )
            return@BoxWithConstraints
        }
        val gap = if (state.compactDensity) 4.dp else 8.dp
        val available = maxWidth
        val compact = available < 720.dp
        val columnWidth = if (compact) available else 360.dp

        if (state.columns.isEmpty()) {
            EmptyDeck(onAddColumn)
            return@BoxWithConstraints
        }

        val deckListState = rememberLazyListState()
        LaunchedEffect(deckListState, state.selectedMenu, state.columns.size) {
            val selectedIndex = state.columns.indexOfLast { it.kind == state.selectedMenu }
            if (selectedIndex >= 0) deckListState.animateScrollToItem(selectedIndex)
        }
        LaunchedEffect(deckListState, state.columns) {
            snapshotFlow {
                deckListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    state.columns.getOrNull(item.index)?.id
                }.toSet()
            }
                .distinctUntilChanged()
                .collect(latestVisibleColumnsChanged)
        }
        LazyRow(
            modifier = Modifier.fillMaxSize().testTag("deck-columns"),
            state = deckListState,
            contentPadding = PaddingValues(gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            itemsIndexed(state.columns, key = { _, column -> column.id }) { _, column ->
                val scrollPosition = columnScrollPositions[column.id]
                DeckColumnCard(
                    column = column,
                    width = columnWidth,
                    compactDensity = state.compactDensity,
                    hasSession = state.selectedAccountId != null,
                    timelineState = state.timelines[column.id],
                    notificationState = state.notifications[column.id],
                    trendState = state.trends[column.id],
                    messageState = state.messages[column.id],
                    pendingPostActions = state.pendingPostActions,
                    failedPostActions = state.failedPostActions,
                    scrollPosition = scrollPosition,
                    onScrollPositionChanged = { index, offset, key ->
                        onSaveColumnScrollPosition(column.id, index, offset, key)
                    },
                    onPostClick = onPostClick,
                    onQuoteClick = onQuoteClick,
                    onReplyClick = onReplyClick,
                    onRepostClick = onRepostClick,
                    onLikeClick = onLikeClick,
                    onImpressionClick = onImpressionClick,
                    onBookmarkClick = onBookmarkClick,
                    onShareClick = onShareClick,
                    onDownloadClick = onDownloadClick,
                    onRemove = { onRemoveColumn(column.id) },
                    onOpenAccounts = onOpenAccounts,
                    onRetry = { onRefreshColumn(column.id) },
                    onLoadMore = { onLoadMoreColumn(column.id) },
                    onClearNewPosts = { onClearNewPostsColumn(column.id) },
                    modifier = Modifier.pointerInput(column.id, columnWidth) {
                        var accumulatedX = 0f
                        val threshold = columnWidth.toPx() * 0.20f
                        detectDragGesturesAfterLongPress(
                            onDragEnd = { accumulatedX = 0f },
                            onDragCancel = { accumulatedX = 0f },
                        ) { change, dragAmount ->
                            accumulatedX += dragAmount.x
                            if (kotlin.math.abs(accumulatedX) >= threshold) {
                                onMoveColumn(column.id, if (accumulatedX > 0) 1 else -1)
                                accumulatedX = 0f
                            }
                            change.consume()
                        }
                    },
                )
            }
            item(key = "inline-add") {
                InlineAddColumn(width = columnWidth, onClick = onAddColumn)
            }
        }
    }
}

@Composable
private fun EmptyDeck(onAddColumn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onAddColumn)
            .testTag("empty-deck")
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_deck_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_deck_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DeckColumnCard(
    column: DeckColumn,
    width: Dp,
    compactDensity: Boolean,
    hasSession: Boolean,
    timelineState: ColumnTimelineState?,
    notificationState: NotificationColumnState?,
    trendState: TrendColumnState?,
    messageState: DirectMessageColumnState?,
    pendingPostActions: Map<String, Set<PostActionType>>,
    failedPostActions: Map<String, Set<PostActionType>>,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
    onPostClick: (String) -> Unit,
    onQuoteClick: (String) -> Unit,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onImpressionClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    onRemove: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onClearNewPosts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .testTag("column-${column.id}"),
        shape = RoundedCornerShape(if (compactDensity) 8.dp else 12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.column_kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        column.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.testTag("remove-column")) {
                    Icon(Icons.Default.Close, stringResource(R.string.remove_column))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (hasSession) {
                    when (column.kind) {
                        ColumnKind.NOTIFICATIONS -> NotificationBody(
                            notificationState,
                            onRetry,
                            scrollPosition,
                            onScrollPositionChanged,
                        )
                        ColumnKind.TRENDS -> TrendBody(
                            trendState,
                            onRetry,
                            scrollPosition,
                            onScrollPositionChanged,
                        )
                        ColumnKind.MESSAGES -> DirectMessageBody(
                            messageState,
                            onRetry,
                            scrollPosition,
                            onScrollPositionChanged,
                        )
                        else -> TimelineBody(
                            state = timelineState,
                            onRetry = onRetry,
                            onLoadMore = onLoadMore,
                            onClearNewPosts = onClearNewPosts,
                            scrollPosition = scrollPosition,
                            onScrollPositionChanged = onScrollPositionChanged,
                            onPostClick = onPostClick,
                            onQuoteClick = onQuoteClick,
                            onReplyClick = onReplyClick,
                            onRepostClick = onRepostClick,
                            onLikeClick = onLikeClick,
                            onImpressionClick = onImpressionClick,
                            onBookmarkClick = onBookmarkClick,
                            onShareClick = onShareClick,
                            onDownloadClick = onDownloadClick,
                            pendingPostActions = pendingPostActions,
                            failedPostActions = failedPostActions,
                        )
                    }
                } else {
                    val shortViewport = maxHeight < 520.dp
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = (if (shortViewport) {
                            Modifier.fillMaxWidth().verticalScroll(scrollState)
                        } else {
                            Modifier.fillMaxSize()
                        }).padding(if (shortViewport || compactDensity) 18.dp else 26.dp),
                        verticalArrangement = if (shortViewport) Arrangement.Top else Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.login_required_title),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.login_required_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onOpenAccounts) {
                            Text(stringResource(R.string.open_accounts))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectMessageBody(
    state: DirectMessageColumnState?,
    onRetry: () -> Unit,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
) {
    when (state?.status ?: TimelineLoadStatus.IDLE) {
        TimelineLoadStatus.IDLE -> Button(onClick = onRetry) { Text(stringResource(R.string.load_messages)) }
        TimelineLoadStatus.LOADING -> CircularProgressIndicator()
        TimelineLoadStatus.FAILED -> Button(
            onClick = onRetry,
            modifier = Modifier.testTag("message-load-failed"),
        ) { Text(stringResource(R.string.retry)) }
        TimelineLoadStatus.READY -> {
            val readyState = requireNotNull(state)
            val messages = readyState.page?.messages.orEmpty()
            if (messages.isEmpty()) {
                Text(stringResource(R.string.messages_empty), Modifier.testTag("message-empty"))
            } else {
                val listState = rememberRestoredLazyListState(
                    scrollPosition,
                    messages.map { it.id },
                )
                ObserveListScrollPosition(listState, onScrollPositionChanged)
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("message-list"),
                        state = listState,
                    ) {
                        items(messages, key = { it.id }) { message ->
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(
                                    message.senderName ?: message.senderUsername ?: message.senderId,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                message.senderUsername?.let {
                                    Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(message.text)
                                HorizontalDivider(Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    if (readyState.isRefreshing) {
                        SmallRefreshIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationBody(
    state: NotificationColumnState?,
    onRetry: () -> Unit,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
) {
    when (state?.status ?: TimelineLoadStatus.IDLE) {
        TimelineLoadStatus.IDLE -> Button(onClick = onRetry) { Text(stringResource(R.string.load_notifications)) }
        TimelineLoadStatus.LOADING -> CircularProgressIndicator()
        TimelineLoadStatus.FAILED -> Button(
            onClick = onRetry,
            modifier = Modifier.testTag("notification-load-failed"),
        ) { Text(stringResource(R.string.retry)) }
        TimelineLoadStatus.READY -> {
            val readyState = requireNotNull(state)
            val notifications = readyState.page?.notifications.orEmpty()
            if (notifications.isEmpty()) {
                Text(stringResource(R.string.notifications_empty), Modifier.testTag("notification-empty"))
            } else {
                val listState = rememberRestoredLazyListState(
                    scrollPosition,
                    notifications.map { it.id },
                )
                ObserveListScrollPosition(listState, onScrollPositionChanged)
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("notification-list"),
                        state = listState,
                    ) {
                        items(notifications, key = { it.id }) { notification ->
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(notification.kind, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(notification.text)
                                if (notification.actors.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        notification.actors.mapNotNull { it.username }.take(3)
                                            .joinToString(" · ") { "@$it" },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider(Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    if (readyState.isRefreshing) {
                        SmallRefreshIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendBody(
    state: TrendColumnState?,
    onRetry: () -> Unit,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    when (state?.status ?: TimelineLoadStatus.IDLE) {
        TimelineLoadStatus.IDLE -> Button(onClick = onRetry) { Text(stringResource(R.string.load_trends)) }
        TimelineLoadStatus.LOADING -> CircularProgressIndicator()
        TimelineLoadStatus.FAILED -> Button(
            onClick = onRetry,
            modifier = Modifier.testTag("trend-load-failed"),
        ) { Text(stringResource(R.string.retry)) }
        TimelineLoadStatus.READY -> {
            val readyState = requireNotNull(state)
            val filtered = readyState.page?.trends.orEmpty().filter { trend ->
                query.isBlank() || listOf(trend.name, trend.description, trend.domainContext)
                    .filterNotNull()
                    .any { it.contains(query, ignoreCase = true) }
            }
            val listState = rememberRestoredLazyListState(
                scrollPosition,
                filtered.map { it.name },
            )
            ObserveListScrollPosition(listState, onScrollPositionChanged)
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(100) },
                        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("trend-filter"),
                        label = { Text(stringResource(R.string.filter_trends)) },
                        singleLine = true,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("trend-list"),
                        state = listState,
                    ) {
                        items(filtered, key = { it.name }) { trend ->
                            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                                trend.rank?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column {
                                    Text(trend.name, fontWeight = FontWeight.SemiBold)
                                    trend.description?.let {
                                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (readyState.isRefreshing) {
                    SmallRefreshIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineBody(
    state: ColumnTimelineState?,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onClearNewPosts: () -> Unit,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
    onPostClick: (String) -> Unit,
    onQuoteClick: (String) -> Unit,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onImpressionClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    pendingPostActions: Map<String, Set<PostActionType>>,
    failedPostActions: Map<String, Set<PostActionType>>,
) {
    val timelineScope = rememberCoroutineScope()
    when (state?.status ?: TimelineLoadStatus.IDLE) {
        TimelineLoadStatus.IDLE -> Button(onClick = onRetry) {
            Text(stringResource(R.string.load_timeline))
        }
        TimelineLoadStatus.LOADING -> CircularProgressIndicator(Modifier.testTag("timeline-loading"))
        TimelineLoadStatus.FAILED -> {
            Text(
                stringResource(R.string.timeline_load_failed),
                modifier = Modifier.testTag("timeline-load-failed"),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        TimelineLoadStatus.READY -> {
            val readyState = requireNotNull(state)
            if (readyState.posts.isEmpty()) {
                Text(stringResource(R.string.timeline_empty), Modifier.testTag("timeline-empty"))
            } else {
                val listState = rememberRestoredLazyListState(
                    scrollPosition,
                    timelineItemKeys(readyState),
                )
                ObserveListScrollPosition(listState, onScrollPositionChanged)
                LaunchedEffect(listState, readyState.nextCursor, readyState.isLoadingMore) {
                    snapshotFlow {
                        val layout = listState.layoutInfo
                        val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                        layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 3
                    }
                        .distinctUntilChanged()
                        .filter { it && readyState.nextCursor != null && !readyState.isLoadingMore }
                        .collect { onLoadMore() }
                }
                PullToRefreshBox(
                    isRefreshing = readyState.isRefreshing,
                    onRefresh = onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("timeline-post-count-${readyState.posts.size}"),
                ) {
                    Box(
                        Modifier.size(1.dp)
                            .testTag("timeline-refresh-generation-${readyState.refreshGeneration}"),
                    )
                    if (readyState.isRefreshing) {
                        Box(Modifier.size(1.dp).testTag("timeline-refreshing"))
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("timeline-posts"),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        if (readyState.refreshFailed) {
                            item(key = "refresh-failed") {
                                TextButton(
                                    onClick = onRetry,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.retry_latest_posts)) }
                            }
                        }
                        if (readyState.newPostCount > 0) {
                            item(key = "new-posts") {
                                TextButton(
                                    onClick = {
                                        onClearNewPosts()
                                        timelineScope.launch { listState.animateScrollToItem(0) }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("new-posts-banner"),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        readyState.newPostAvatarUrls.take(5).forEach { avatarUrl ->
                                            AsyncImage(
                                                model = safeImageUrl(avatarUrl),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.surface,
                                                        CircleShape,
                                                    ),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                        if (readyState.newPostAvatarUrls.isNotEmpty()) {
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            pluralStringResource(
                                                R.plurals.new_posts_count,
                                                readyState.newPostCount,
                                                readyState.newPostCount,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        items(readyState.posts, key = Post::id) { post ->
                            PostCard(
                                post = post,
                                onPostClick = onPostClick,
                                onQuoteClick = onQuoteClick,
                                onReplyClick = onReplyClick,
                                onRepostClick = onRepostClick,
                                onLikeClick = onLikeClick,
                                onImpressionClick = onImpressionClick,
                                onBookmarkClick = onBookmarkClick,
                                onShareClick = onShareClick,
                                onDownloadClick = onDownloadClick,
                                pendingActions = pendingPostActions[post.id].orEmpty(),
                                failedActions = failedPostActions[post.id].orEmpty(),
                            )
                        }
                        if (readyState.isLoadingMore) {
                            item(key = "loading-more") {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                }
                            }
                        } else if (readyState.loadMoreFailed) {
                            item(key = "load-more-failed") {
                                TextButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier.fillMaxWidth().testTag("load-more-failed"),
                                ) { Text(stringResource(R.string.retry_older_posts)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberRestoredLazyListState(
    scrollPosition: ColumnScrollPosition?,
    itemKeys: List<String>,
): LazyListState = rememberLazyListState(
    initialFirstVisibleItemIndex = restoredItemIndex(scrollPosition, itemKeys),
    initialFirstVisibleItemScrollOffset = scrollPosition
        ?.firstVisibleItemScrollOffset
        ?.coerceAtLeast(0)
        ?: 0,
)

@Composable
private fun ObserveListScrollPosition(
    listState: LazyListState,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
) {
    val latestOnScrollPositionChanged by rememberUpdatedState(onScrollPositionChanged)
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString(),
            )
        }
            .distinctUntilChanged()
            .collect { (index, offset, key) ->
                latestOnScrollPositionChanged(index, offset, key)
            }
    }
}

@Composable
private fun SmallRefreshIndicator(modifier: Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(18.dp),
        strokeWidth = 2.dp,
    )
}

private fun restoredItemIndex(
    scrollPosition: ColumnScrollPosition?,
    itemKeys: List<String>,
): Int {
    if (itemKeys.isEmpty()) return 0
    val keyIndex = scrollPosition?.firstVisibleItemKey
        ?.let(itemKeys::indexOf)
        ?.takeIf { it >= 0 }
    return (keyIndex ?: scrollPosition?.firstVisibleItemIndex ?: 0).coerceIn(0, itemKeys.lastIndex)
}

private fun timelineItemKeys(state: ColumnTimelineState): List<String> = buildList {
    if (state.refreshFailed) add("refresh-failed")
    if (state.newPostCount > 0) add("new-posts")
    addAll(state.posts.map(Post::id))
    if (state.isLoadingMore) add("loading-more")
    if (state.loadMoreFailed) add("load-more-failed")
}

internal fun safeImageUrl(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val uri = value.toUri()
        val host = uri.host?.lowercase().orEmpty()
        value.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                (host == "twimg.com" || host.endsWith(".twimg.com"))
        }
    }.getOrNull()
}

@Composable
private fun InlineAddColumn(width: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .testTag("inline-add-column"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Add, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.add_column), color = MaterialTheme.colorScheme.primary)
        }
    }
}
