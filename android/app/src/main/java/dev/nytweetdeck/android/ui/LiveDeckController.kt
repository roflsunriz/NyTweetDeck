package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.xapi.live.LivePipelineEvent
import dev.nytweetdeck.android.xapi.live.LivePipelineSubscriptionService
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class LiveDeckController(
    private val service: LivePipelineSubscriptionService?,
    private val scope: CoroutineScope,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
    private val onDirectMessagesChanged: (String) -> Unit,
) : AutoCloseable {
    private var visibleColumnIds: Set<String> = emptySet()
    private var foreground = false
    private var subscribedAccountId: String? = null
    private val listener = service?.addListener(::onEvent)
    private val observationJob = service?.let {
        scope.launch {
            state.map { snapshot ->
                val accountId = snapshot.selectedAccountId
                val ids = visibleColumnIds.flatMap { snapshot.timelines[it]?.posts.orEmpty() }
                    .map(Post::id)
                    .distinct()
                    .take(MAX_TOPICS)
                val dm = snapshot.columns.any { column ->
                    column.id in visibleColumnIds && column.kind == ColumnKind.MESSAGES
                }
                Triple(accountId, ids, dm)
            }.collectLatest { updateSubscription() }
        }
    }

    fun setVisibleColumns(columnIds: Set<String>) {
        visibleColumnIds = columnIds
        updateSubscription()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) updateSubscription() else stop()
    }

    private fun updateSubscription() {
        val live = service ?: return
        if (!foreground) return
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        if (subscribedAccountId != null && subscribedAccountId != accountId) {
            live.closeAccount(requireNotNull(subscribedAccountId))
        }
        val postIds = visibleColumnIds.flatMap { snapshot.timelines[it]?.posts.orEmpty() }
            .map(Post::id)
            .distinct()
            .take(MAX_TOPICS)
        val directMessages = snapshot.columns.any {
            it.id in visibleColumnIds && it.kind == ColumnKind.MESSAGES
        }
        val status = live.update(account, SUBSCRIBER_ID, postIds, directMessages)
        subscribedAccountId = accountId
        state.value = state.value.copy(
            liveConnected = status.connected,
            liveError = status.lastErrorKind?.name,
            liveLastEventAt = status.lastEventAt?.toString(),
        )
    }

    private fun stop() {
        subscribedAccountId?.let { service?.closeAccount(it) }
        subscribedAccountId = null
        state.value = state.value.copy(liveConnected = false)
    }

    private fun onEvent(event: LivePipelineEvent) {
        if (event.accountId != state.value.selectedAccountId) return
        when (event) {
            is LivePipelineEvent.Connected -> updateLiveState(true, null, event.occurredAt)
            is LivePipelineEvent.Error -> updateLiveState(false, event.kind.name, event.occurredAt)
            is LivePipelineEvent.Engagement -> event.postId?.let { updateEngagement(it, event) }
            is LivePipelineEvent.DirectMessageUpdate -> visibleMessageColumns().forEach(onDirectMessagesChanged)
            is LivePipelineEvent.DirectMessageTyping,
            is LivePipelineEvent.LiveContent -> updateLiveState(true, null, event.occurredAt)
        }
    }

    private fun updateEngagement(postId: String, event: LivePipelineEvent.Engagement) {
        state.value = state.value.let { current ->
            current.copy(
                timelines = current.timelines.mapValues { (_, timeline) ->
                    timeline.copy(posts = timeline.posts.map { post ->
                        if (post.id == postId) post.withLiveCounts(event) else post
                    })
                },
                liveConnected = true,
                liveError = null,
                liveLastEventAt = event.occurredAt.toString(),
            )
        }
    }

    private fun visibleMessageColumns(): List<String> = state.value.columns
        .filter { it.id in visibleColumnIds && it.kind == ColumnKind.MESSAGES }
        .map { it.id }

    private fun updateLiveState(connected: Boolean, error: String?, at: Instant) {
        state.value = state.value.copy(
            liveConnected = connected,
            liveError = error,
            liveLastEventAt = at.toString(),
        )
    }

    override fun close() {
        observationJob?.cancel()
        listener?.close()
        stop()
    }

    private companion object {
        const val SUBSCRIBER_ID = "android-visible-columns"
        const val MAX_TOPICS = 100
    }
}

private fun Post.withLiveCounts(event: LivePipelineEvent.Engagement): Post = copy(
    replyCount = event.counts.replyCount ?: replyCount,
    repostCount = event.counts.repostCount ?: repostCount,
    quoteCount = event.counts.quoteCount ?: quoteCount,
    likeCount = event.counts.likeCount ?: likeCount,
    bookmarkCount = event.counts.bookmarkCount ?: bookmarkCount,
    viewCount = event.counts.viewCount ?: viewCount,
)
