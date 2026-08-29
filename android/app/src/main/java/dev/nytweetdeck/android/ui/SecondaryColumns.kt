package dev.nytweetdeck.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.ColumnScrollPosition
import dev.nytweetdeck.android.model.DirectMessageColumnState
import dev.nytweetdeck.android.model.Notification
import dev.nytweetdeck.android.model.NotificationColumnState
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.model.TrendColumnState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
internal fun DirectMessageBody(
    state: DirectMessageColumnState?,
    onRetry: () -> Unit,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
    onLoadMore: () -> Unit,
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
                ObserveSecondaryPaging(
                    listState,
                    readyState.page?.nextCursor,
                    readyState.isLoadingMore,
                    onLoadMore,
                )
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
                        item(key = "message-load-more") {
                            SecondaryLoadMoreFooter(
                                readyState.isLoadingMore,
                                readyState.loadMoreFailed,
                                onLoadMore,
                                "message-load-more",
                            )
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
internal fun NotificationBody(
    state: NotificationColumnState?,
    onRetry: () -> Unit,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
    onLoadMore: () -> Unit,
    onNotificationClick: (Notification) -> Unit,
) {
    when (state?.status ?: TimelineLoadStatus.IDLE) {
        TimelineLoadStatus.IDLE -> Button(onClick = onRetry) {
            Text(stringResource(R.string.load_notifications))
        }
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
                ObserveSecondaryPaging(
                    listState,
                    readyState.page?.nextCursor,
                    readyState.isLoadingMore,
                    onLoadMore,
                )
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("notification-list"),
                        state = listState,
                    ) {
                        items(notifications, key = { it.id }) { notification ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNotificationClick(notification) }
                                    .padding(14.dp)
                                    .testTag("notification-item-" + notification.id.hashCode()),
                            ) {
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
                        item(key = "notification-load-more") {
                            SecondaryLoadMoreFooter(
                                readyState.isLoadingMore,
                                readyState.loadMoreFailed,
                                onLoadMore,
                                "notification-load-more",
                            )
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
internal fun TrendBody(
    state: TrendColumnState?,
    onRetry: () -> Unit,
    scrollPosition: ColumnScrollPosition?,
    onScrollPositionChanged: (Int, Int, String?) -> Unit,
    trendSearchHistory: List<String>,
    onTrendSelected: (String) -> Unit,
    onTrendQueryCommitted: (String) -> Unit,
    onClearTrendHistory: () -> Unit,
    onLoadMore: () -> Unit,
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
            ObserveSecondaryPaging(
                listState,
                readyState.page?.nextCursor,
                readyState.isLoadingMore,
                onLoadMore,
            )
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(100) },
                        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("trend-filter"),
                        label = { Text(stringResource(R.string.filter_trends)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            query.trim().takeIf(String::isNotEmpty)?.let(onTrendQueryCommitted)
                        }),
                    )
                    if (trendSearchHistory.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            trendSearchHistory.forEach { history ->
                                TextButton(
                                    onClick = { query = history },
                                    modifier = Modifier.testTag("trend-history-" + history.hashCode()),
                                ) { Text(history) }
                            }
                            TextButton(
                                onClick = onClearTrendHistory,
                                modifier = Modifier.testTag("clear-trend-history"),
                            ) { Text(stringResource(R.string.clear_history)) }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("trend-list"),
                        state = listState,
                    ) {
                        items(filtered, key = { it.name }) { trend ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onTrendSelected(trend.name) }
                                    .padding(14.dp)
                                    .testTag("trend-item-" + trend.name.hashCode()),
                            ) {
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
                        item(key = "trend-load-more") {
                            SecondaryLoadMoreFooter(
                                readyState.isLoadingMore,
                                readyState.loadMoreFailed,
                                onLoadMore,
                                "trend-load-more",
                            )
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

@Composable
private fun ObserveSecondaryPaging(
    listState: LazyListState,
    nextCursor: String?,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, nextCursor, isLoadingMore) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it && nextCursor != null && !isLoadingMore }
            .collect { onLoadMore() }
    }
}

@Composable
private fun SecondaryLoadMoreFooter(
    isLoadingMore: Boolean,
    loadMoreFailed: Boolean,
    onLoadMore: () -> Unit,
    tag: String,
) {
    when {
        isLoadingMore -> Box(
            Modifier.fillMaxWidth().padding(12.dp).testTag(tag),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        loadMoreFailed -> TextButton(
            onClick = onLoadMore,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        ) { Text(stringResource(R.string.retry)) }
        else -> Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun SmallRefreshIndicator(modifier: Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(18.dp),
        strokeWidth = 2.dp,
    )
}
