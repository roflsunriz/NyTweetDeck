package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.DirectMessageRepository
import dev.nytweetdeck.android.data.NotificationRepository
import dev.nytweetdeck.android.data.TrendRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.DirectMessageColumnState
import dev.nytweetdeck.android.model.NotificationColumnState
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.model.TrendColumnState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SecondaryColumnRefreshController(
    private val notificationRepository: NotificationRepository?,
    private val trendRepository: TrendRepository?,
    private val directMessageRepository: DirectMessageRepository?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<DeckUiState>,
    private val onFresh: (String) -> Unit,
) {
    fun refreshNotifications(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = notificationRepository ?: return
        val previous = state.value.notifications[columnId]
        if (previous?.status == TimelineLoadStatus.LOADING || previous?.isRefreshing == true) return
        state.update { current ->
            current.copy(
                notifications = current.notifications + (
                    columnId to if (previous?.status == TimelineLoadStatus.READY) {
                        previous.copy(isRefreshing = true, refreshFailed = false)
                    } else {
                        NotificationColumnState(status = TimelineLoadStatus.LOADING)
                    }
                ),
            )
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                    repository.load(account, language = state.value.appLanguageTag)
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId || state.value.columns.none { it.id == columnId }) return@withContext
                if (result.isSuccess) onFresh(columnId)
                state.update { current ->
                    val existing = current.notifications[columnId]
                    current.copy(
                        notifications = current.notifications + (
                            columnId to result.fold(
                                onSuccess = { page ->
                                    val existingIds = existing?.page?.notifications
                                        ?.mapTo(HashSet()) { it.id }
                                        .orEmpty()
                                    val newItems = if (existing?.status == TimelineLoadStatus.READY) {
                                        page.notifications.filterNot { it.id in existingIds }
                                    } else {
                                        emptyList()
                                    }
                                    val mergedNotifications = LinkedHashMap<String, dev.nytweetdeck.android.model.Notification>()
                                    page.notifications.forEach { mergedNotifications[it.id] = it }
                                    existing?.page?.notifications?.forEach { mergedNotifications.putIfAbsent(it.id, it) }
                                    val mergedPosts = LinkedHashMap<String, dev.nytweetdeck.android.model.Post>()
                                    page.posts.forEach { mergedPosts[it.id] = it }
                                    existing?.page?.posts?.forEach { mergedPosts.putIfAbsent(it.id, it) }
                                    NotificationColumnState(
                                        status = TimelineLoadStatus.READY,
                                        page = page.copy(
                                            notifications = mergedNotifications.values.toList(),
                                            posts = mergedPosts.values.toList(),
                                            nextCursor = existing?.page?.nextCursor ?: page.nextCursor,
                                        ),
                                        newItemCount = newItems.size,
                                        newItemAvatarUrls = newItems.flatMap { item ->
                                            item.actors.mapNotNull { it.avatarUrl }
                                        }.distinct().take(5),
                                        refreshGeneration = if (existing?.status == TimelineLoadStatus.READY) {
                                            existing.refreshGeneration + 1
                                        } else {
                                            existing?.refreshGeneration ?: 0
                                        },
                                    )
                                },
                                onFailure = {
                                    if (existing?.page != null) {
                                        existing.copy(
                                            status = TimelineLoadStatus.READY,
                                            isRefreshing = false,
                                            refreshFailed = true,
                                            refreshGeneration = existing.refreshGeneration + 1,
                                        )
                                    } else {
                                        NotificationColumnState(status = TimelineLoadStatus.FAILED)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }
    }

    fun refreshTrends(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = trendRepository ?: return
        val previous = state.value.trends[columnId]
        if (previous?.status == TimelineLoadStatus.LOADING || previous?.isRefreshing == true) return
        state.update { current ->
            current.copy(
                trends = current.trends + (
                    columnId to if (previous?.status == TimelineLoadStatus.READY) {
                        previous.copy(isRefreshing = true, refreshFailed = false)
                    } else {
                        TrendColumnState(status = TimelineLoadStatus.LOADING)
                    }
                ),
            )
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                    repository.load(account, language = state.value.appLanguageTag)
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId || state.value.columns.none { it.id == columnId }) return@withContext
                if (result.isSuccess) onFresh(columnId)
                state.update { current ->
                    val existing = current.trends[columnId]
                    current.copy(
                        trends = current.trends + (
                            columnId to result.fold(
                                onSuccess = { page ->
                                    val existingKeys = existing?.page?.trends
                                        ?.mapTo(HashSet()) { it.url }
                                        .orEmpty()
                                    val newCount = if (existing?.status == TimelineLoadStatus.READY) {
                                        page.trends.count { it.url !in existingKeys }
                                    } else {
                                        0
                                    }
                                    TrendColumnState(
                                        status = TimelineLoadStatus.READY,
                                        page = page,
                                        newItemCount = newCount,
                                        refreshGeneration = if (existing?.status == TimelineLoadStatus.READY) {
                                            existing.refreshGeneration + 1
                                        } else {
                                            existing?.refreshGeneration ?: 0
                                        },
                                    )
                                },
                                onFailure = {
                                    if (existing?.page != null) {
                                        existing.copy(
                                            status = TimelineLoadStatus.READY,
                                            isRefreshing = false,
                                            refreshFailed = true,
                                            refreshGeneration = existing.refreshGeneration + 1,
                                        )
                                    } else {
                                        TrendColumnState(status = TimelineLoadStatus.FAILED)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }
    }

    fun refreshMessages(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = directMessageRepository ?: return
        val previous = state.value.messages[columnId]
        if (previous?.status == TimelineLoadStatus.LOADING || previous?.isRefreshing == true) return
        state.update { current ->
            current.copy(
                messages = current.messages + (
                    columnId to if (previous?.status == TimelineLoadStatus.READY) {
                        previous.copy(isRefreshing = true, refreshFailed = false)
                    } else {
                        DirectMessageColumnState(status = TimelineLoadStatus.LOADING)
                    }
                ),
            )
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                    repository.load(account, language = state.value.appLanguageTag)
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId || state.value.columns.none { it.id == columnId }) return@withContext
                if (result.isSuccess) onFresh(columnId)
                state.update { current ->
                    val existing = current.messages[columnId]
                    current.copy(
                        messages = current.messages + (
                            columnId to result.fold(
                                onSuccess = { page ->
                                    val existingIds = existing?.page?.messages
                                        ?.mapTo(HashSet()) { it.id }
                                        .orEmpty()
                                    val newItems = if (existing?.status == TimelineLoadStatus.READY) {
                                        page.messages.filterNot { it.id in existingIds }
                                    } else {
                                        emptyList()
                                    }
                                    val merged = LinkedHashMap<String, dev.nytweetdeck.android.model.DirectMessage>()
                                    page.messages.forEach { merged[it.id] = it }
                                    existing?.page?.messages?.forEach { merged.putIfAbsent(it.id, it) }
                                    DirectMessageColumnState(
                                        status = TimelineLoadStatus.READY,
                                        page = page.copy(
                                            messages = merged.values.toList(),
                                            nextCursor = existing?.page?.nextCursor ?: page.nextCursor,
                                        ),
                                        newItemCount = newItems.size,
                                        newItemAvatarUrls = newItems.mapNotNull { it.senderAvatarUrl }
                                            .distinct()
                                            .take(5),
                                        refreshGeneration = if (existing?.status == TimelineLoadStatus.READY) {
                                            existing.refreshGeneration + 1
                                        } else {
                                            existing?.refreshGeneration ?: 0
                                        },
                                    )
                                },
                                onFailure = {
                                    if (existing?.page != null) {
                                        existing.copy(
                                            status = TimelineLoadStatus.READY,
                                            isRefreshing = false,
                                            refreshFailed = true,
                                            refreshGeneration = existing.refreshGeneration + 1,
                                        )
                                    } else {
                                        DirectMessageColumnState(status = TimelineLoadStatus.FAILED)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }
    }

}
