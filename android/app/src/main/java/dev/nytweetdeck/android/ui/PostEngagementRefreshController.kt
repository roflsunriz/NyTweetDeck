package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostActionType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Restored pages can retain posts that are absent from the next first-page response. */
internal class PostEngagementRefreshController(
    private val repository: PostDetailRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    private var restoredAccountId: String? = null
    private val staleIds = mutableSetOf<String>()
    private val inFlightIds = mutableSetOf<String>()

    fun seed(accountId: String?, posts: Collection<Post>) {
        restoredAccountId = accountId
        staleIds.clear()
        staleIds.addAll(posts.map(Post::id))
        inFlightIds.clear()
    }

    fun discardFresh(accountId: String, posts: Collection<Post>) {
        if (accountId == restoredAccountId) staleIds.removeAll(posts.map(Post::id).toSet())
    }

    fun refreshVisible(postIds: Set<String>) {
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return
        if (accountId != restoredAccountId) return
        val account = accountProvider(accountId) ?: return
        postIds.filter { it in staleIds && inFlightIds.add(it) }.forEach { postId ->
            scope.launch(ioDispatcher) {
                val result = runCatching { repository.loadFocal(account, postId, snapshot.appLanguageTag) }
                withContext(Dispatchers.Main.immediate) {
                    inFlightIds.remove(postId)
                    if (accountId != restoredAccountId || state.value.selectedAccountId != accountId) return@withContext
                    result.onSuccess { fresh ->
                        staleIds.remove(postId)
                        state.update { current ->
                            val pending = current.pendingPostActions[postId].orEmpty()
                            current.copy(
                                timelines = current.timelines.mapValues { (_, timeline) ->
                                    timeline.copy(posts = timeline.posts.map { post ->
                                        if (post.id == postId) post.withFreshEngagement(fresh, pending) else post
                                    })
                                },
                                notifications = current.notifications.mapValues { (_, column) ->
                                    column.copy(page = column.page?.let { page ->
                                        page.copy(posts = page.posts.map { post ->
                                            if (post.id == postId) post.withFreshEngagement(fresh, pending) else post
                                        })
                                    })
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Post.withFreshEngagement(fresh: Post, pending: Set<PostActionType>): Post = copy(
    liked = if (PostActionType.LIKE in pending) liked else fresh.liked,
    reposted = if (PostActionType.REPOST in pending) reposted else fresh.reposted,
    bookmarked = if (PostActionType.BOOKMARK in pending) bookmarked else fresh.bookmarked,
    likeCount = if (PostActionType.LIKE in pending) likeCount else fresh.likeCount,
    repostCount = if (PostActionType.REPOST in pending) repostCount else fresh.repostCount,
    bookmarkCount = if (PostActionType.BOOKMARK in pending) bookmarkCount else fresh.bookmarkCount,
)
