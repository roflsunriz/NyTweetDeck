package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.DirectMessageRepository
import dev.nytweetdeck.android.data.NotificationRepository
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.data.TrendRepository
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.TimelineLoadStatus
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ColumnPagingController(
    private val timelineRepository: TimelineRepository?,
    private val notificationRepository: NotificationRepository?,
    private val trendRepository: TrendRepository?,
    private val directMessageRepository: DirectMessageRepository?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    fun loadMore(columnId: String) {
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        val column = snapshot.columns.firstOrNull { it.id == columnId } ?: return
        when (column.kind) {
            ColumnKind.NOTIFICATIONS -> loadMoreNotifications(columnId, accountId, account)
            ColumnKind.TRENDS -> loadMoreTrends(columnId, accountId, account)
            ColumnKind.MESSAGES -> loadMoreMessages(columnId, accountId, account)
            else -> loadMoreTimeline(columnId, accountId, account)
        }
    }

    private fun loadMoreTimeline(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = timelineRepository ?: return
        val snapshot = state.value
        val column = snapshot.columns.firstOrNull { it.id == columnId } ?: return
        val kind = queryKind(column.kind) ?: return
        val timeline = snapshot.timelines[columnId] ?: return
        val cursor = timeline.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (timeline.status != TimelineLoadStatus.READY || timeline.isLoadingMore) return
        state.update { current ->
            current.copy(timelines = current.timelines + (
                columnId to timeline.copy(isLoadingMore = true, loadMoreFailed = false)
            ))
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.load(
                    account,
                    kind,
                    column.target,
                    cursor,
                    snapshot.appLanguageTag,
                    column.sort.name.lowercase(Locale.ROOT),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId) return@withContext
                state.update { current ->
                    val existing = current.timelines[columnId] ?: return@update current
                    result.fold(
                        onSuccess = { page ->
                            val merged = LinkedHashMap<String, dev.nytweetdeck.android.model.Post>()
                            existing.posts.forEach { merged[it.id] = it }
                            page.posts.forEach { merged.putIfAbsent(it.id, it) }
                            current.copy(timelines = current.timelines + (
                                columnId to existing.copy(
                                    posts = merged.values.toList(),
                                    nextCursor = page.nextCursor,
                                    isLoadingMore = false,
                                    loadMoreFailed = false,
                                )
                            ))
                        },
                        onFailure = { current.copy(timelines = current.timelines + (
                            columnId to existing.copy(isLoadingMore = false, loadMoreFailed = true)
                        )) },
                    )
                }
            }
        }
    }

    private fun loadMoreNotifications(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = notificationRepository ?: return
        val current = state.value.notifications[columnId] ?: return
        val cursor = current.page?.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (current.status != TimelineLoadStatus.READY || current.isLoadingMore) return
        state.update { it.copy(notifications = it.notifications + (
            columnId to current.copy(isLoadingMore = true, loadMoreFailed = false)
        )) }
        scope.launch(ioDispatcher) {
            val result = runCatching { repository.load(account, cursor, language()) }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId) return@withContext
                state.update { snapshot ->
                    val existing = snapshot.notifications[columnId] ?: return@update snapshot
                    result.fold(
                        onSuccess = { page ->
                            val notifications = LinkedHashMap<String, dev.nytweetdeck.android.model.Notification>()
                            existing.page?.notifications.orEmpty().forEach { notifications[it.id] = it }
                            page.notifications.forEach { notifications.putIfAbsent(it.id, it) }
                            val posts = LinkedHashMap<String, dev.nytweetdeck.android.model.Post>()
                            existing.page?.posts.orEmpty().forEach { posts[it.id] = it }
                            page.posts.forEach { posts.putIfAbsent(it.id, it) }
                            snapshot.copy(notifications = snapshot.notifications + (
                                columnId to existing.copy(
                                    page = page.copy(
                                        notifications = notifications.values.toList(),
                                        posts = posts.values.toList(),
                                    ),
                                    isLoadingMore = false,
                                    loadMoreFailed = false,
                                )
                            ))
                        },
                        onFailure = { snapshot.copy(notifications = snapshot.notifications + (
                            columnId to existing.copy(isLoadingMore = false, loadMoreFailed = true)
                        )) },
                    )
                }
            }
        }
    }

    private fun loadMoreTrends(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = trendRepository ?: return
        val current = state.value.trends[columnId] ?: return
        val cursor = current.page?.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (current.status != TimelineLoadStatus.READY || current.isLoadingMore) return
        state.update { it.copy(trends = it.trends + (
            columnId to current.copy(isLoadingMore = true, loadMoreFailed = false)
        )) }
        scope.launch(ioDispatcher) {
            val result = runCatching { repository.load(account, cursor, language()) }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId) return@withContext
                state.update { snapshot ->
                    val existing = snapshot.trends[columnId] ?: return@update snapshot
                    result.fold(
                        onSuccess = { page ->
                            val trends = LinkedHashMap<String, dev.nytweetdeck.android.model.Trend>()
                            existing.page?.trends.orEmpty().forEach { trends[it.url] = it }
                            page.trends.forEach { trends.putIfAbsent(it.url, it) }
                            snapshot.copy(trends = snapshot.trends + (
                                columnId to existing.copy(
                                    page = page.copy(trends = trends.values.toList()),
                                    isLoadingMore = false,
                                    loadMoreFailed = false,
                                )
                            ))
                        },
                        onFailure = { snapshot.copy(trends = snapshot.trends + (
                            columnId to existing.copy(isLoadingMore = false, loadMoreFailed = true)
                        )) },
                    )
                }
            }
        }
    }

    private fun loadMoreMessages(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = directMessageRepository ?: return
        val current = state.value.messages[columnId] ?: return
        val cursor = current.page?.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (current.status != TimelineLoadStatus.READY || current.isLoadingMore) return
        state.update { it.copy(messages = it.messages + (
            columnId to current.copy(isLoadingMore = true, loadMoreFailed = false)
        )) }
        scope.launch(ioDispatcher) {
            val result = runCatching { repository.load(account, cursor, language()) }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId) return@withContext
                state.update { snapshot ->
                    val existing = snapshot.messages[columnId] ?: return@update snapshot
                    result.fold(
                        onSuccess = { page ->
                            val messages = LinkedHashMap<String, dev.nytweetdeck.android.model.DirectMessage>()
                            existing.page?.messages.orEmpty().forEach { messages[it.id] = it }
                            page.messages.forEach { messages.putIfAbsent(it.id, it) }
                            snapshot.copy(messages = snapshot.messages + (
                                columnId to existing.copy(
                                    page = page.copy(messages = messages.values.toList()),
                                    isLoadingMore = false,
                                    loadMoreFailed = false,
                                )
                            ))
                        },
                        onFailure = { snapshot.copy(messages = snapshot.messages + (
                            columnId to existing.copy(isLoadingMore = false, loadMoreFailed = true)
                        )) },
                    )
                }
            }
        }
    }

    private fun language(): String = state.value.appLanguageTag
}
