package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostActionRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostActionType
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PostActionController(
    private val repository: PostActionRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    private val operations = mutableMapOf<ToggleKey, ToggleOperation>()

    fun toggle(postId: String, action: PostActionType) {
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        val post = findPost(snapshot, postId) ?: return
        val currentActive = post.actionActive(action)
        val desiredActive = !currentActive
        val key = ToggleKey(accountId, postId, action)
        val operation = operations.getOrPut(key) {
            ToggleOperation(confirmedActive = currentActive, desiredActive = desiredActive)
        }
        operation.desiredActive = desiredActive
        updatePostActionState(postId, action, desiredActive)
        state.update { current ->
            current.copy(
                pendingPostActions = current.pendingPostActions.withAction(postId, action, add = true),
                failedPostActions = current.failedPostActions.withAction(postId, action, add = false),
            )
        }
        if (operation.job?.isActive == true) return
        operation.job = scope.launch(ioDispatcher) {
            process(key, operation, account)
        }
    }

    fun clearFailure(postId: String, action: PostActionType) {
        state.update { current ->
            current.copy(
                failedPostActions = current.failedPostActions.withAction(postId, action, add = false),
            )
        }
    }

    private suspend fun process(
        key: ToggleKey,
        operation: ToggleOperation,
        account: AccountSecrets,
    ) {
        while (true) {
            while (operation.desiredActive != operation.confirmedActive) {
                val requestedActive = operation.desiredActive
                val result = runCatching {
                    repository.setActive(
                        account = account,
                        postId = key.postId,
                        action = key.action,
                        active = requestedActive,
                        language = Locale.getDefault().toLanguageTag().ifBlank { "ja" },
                    )
                }
                if (result.isFailure) {
                    withContext(Dispatchers.Main.immediate) {
                        if (state.value.selectedAccountId == key.accountId) {
                            updatePostActionState(key.postId, key.action, operation.confirmedActive)
                            state.update { current ->
                                current.copy(
                                    pendingPostActions = current.pendingPostActions
                                        .withAction(key.postId, key.action, add = false),
                                    failedPostActions = current.failedPostActions
                                        .withAction(key.postId, key.action, add = true),
                                )
                            }
                        }
                        operations.remove(key)
                    }
                    return
                }
                operation.confirmedActive = requestedActive
            }
            val completed = withContext(Dispatchers.Main.immediate) {
                if (operation.desiredActive != operation.confirmedActive) {
                    false
                } else {
                    if (state.value.selectedAccountId == key.accountId) {
                        state.update { current ->
                            current.copy(
                                pendingPostActions = current.pendingPostActions
                                    .withAction(key.postId, key.action, add = false),
                            )
                        }
                    }
                    operations.remove(key)
                    true
                }
            }
            if (completed) return
        }
    }

    private fun updatePostActionState(postId: String, action: PostActionType, active: Boolean) {
        state.update { current ->
            current.copy(
                timelines = current.timelines.mapValues { (_, timeline) ->
                    timeline.copy(posts = timeline.posts.map { post ->
                        if (post.id == postId) post.withActionActive(action, active) else post
                    })
                },
                notifications = current.notifications.mapValues { (_, notifications) ->
                    notifications.copy(page = notifications.page?.let { page ->
                        page.copy(posts = page.posts.map { post ->
                            if (post.id == postId) post.withActionActive(action, active) else post
                        })
                    })
                },
                postDetail = current.postDetail.copy(
                    page = current.postDetail.page?.let { page ->
                        page.copy(
                            post = if (page.post.id == postId) {
                                page.post.withActionActive(action, active)
                            } else {
                                page.post
                            },
                            relatedPosts = page.relatedPosts.map { post ->
                                if (post.id == postId) post.withActionActive(action, active) else post
                            },
                            replies = page.replies.map { reply ->
                                if (reply.post.id == postId) {
                                    reply.copy(post = reply.post.withActionActive(action, active))
                                } else {
                                    reply
                                }
                            },
                        )
                    },
                ),
            )
        }
    }
}

private data class ToggleKey(
    val accountId: String,
    val postId: String,
    val action: PostActionType,
)

private data class ToggleOperation(
    @Volatile var confirmedActive: Boolean,
    @Volatile var desiredActive: Boolean,
    var job: Job? = null,
)

private fun findPost(state: DeckUiState, postId: String): Post? =
    state.timelines.values.asSequence().flatMap { it.posts.asSequence() }
        .plus(state.notifications.values.asSequence().flatMap { it.page?.posts.orEmpty().asSequence() })
        .plus(listOfNotNull(state.postDetail.page?.post).asSequence())
        .plus(state.postDetail.page?.relatedPosts.orEmpty().asSequence())
        .plus(state.postDetail.page?.replies.orEmpty().asSequence().map { it.post })
        .firstOrNull { it.id == postId }

private fun Post.actionActive(action: PostActionType): Boolean = when (action) {
    PostActionType.LIKE -> liked
    PostActionType.REPOST -> reposted
    PostActionType.BOOKMARK -> bookmarked
}

private fun Post.withActionActive(action: PostActionType, active: Boolean): Post = when (action) {
    PostActionType.LIKE -> copy(liked = active, likeCount = adjustedCount(likeCount, liked, active))
    PostActionType.REPOST -> copy(
        reposted = active,
        repostCount = adjustedCount(repostCount, reposted, active),
    )
    PostActionType.BOOKMARK -> copy(
        bookmarked = active,
        bookmarkCount = adjustedCount(bookmarkCount, bookmarked, active),
    )
}

private fun adjustedCount(count: Long, wasActive: Boolean, active: Boolean): Long = when {
    wasActive == active -> count
    active -> count + 1
    else -> (count - 1).coerceAtLeast(0)
}

private fun Map<String, Set<PostActionType>>.withAction(
    postId: String,
    action: PostActionType,
    add: Boolean,
): Map<String, Set<PostActionType>> {
    val actions = this[postId].orEmpty()
    val updated = if (add) actions + action else actions - action
    return if (updated.isEmpty()) this - postId else this + (postId to updated)
}
