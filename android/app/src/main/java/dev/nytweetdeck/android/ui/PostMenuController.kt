package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.ListMembershipAction
import dev.nytweetdeck.android.data.ListMembershipExecutor
import dev.nytweetdeck.android.data.UserAction
import dev.nytweetdeck.android.data.UserActionExecutor
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PostMenuController(
    private val userActions: UserActionExecutor?,
    private val listMembership: ListMembershipExecutor?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    private val runningUserActions = mutableSetOf<UserOperationKey>()
    private val runningListActions = mutableSetOf<ListOperationKey>()
    private val listQueues = mutableMapOf<ListOperationKey, ArrayDeque<ListMembershipAction>>()
    private val pendingOperations = mutableSetOf<MenuOperationKey>()
    private val failedOperations = mutableSetOf<MenuOperationKey>()

    fun hide(postId: String) {
        state.update { current ->
            current.copy(
                hiddenPostIds = current.hiddenPostIds + postId,
                timelines = current.timelines.mapValues { (_, timeline) ->
                    timeline.copy(posts = timeline.posts.filterNot { it.id == postId })
                },
                notifications = current.notifications.mapValues { (_, notifications) ->
                    notifications.copy(page = notifications.page?.let { page ->
                        page.copy(posts = page.posts.filterNot { it.id == postId })
                    })
                },
            )
        }
    }

    fun userAction(post: Post, action: UserAction) {
        val repository = userActions ?: return fail()
        val accountId = state.value.selectedAccountId ?: return fail()
        val account = accountProvider(accountId) ?: return fail()
        val key = UserOperationKey(accountId, post.author.id, action)
        if (!runningUserActions.add(key)) return
        begin(key)
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.execute(account, post.author.id, action, language())
            }
            withContext(Dispatchers.Main.immediate) {
                runningUserActions.remove(key)
                finish(key, result.isFailure)
            }
        }
    }

    fun listMembership(post: Post, listId: String, add: Boolean) {
        val repository = listMembership ?: return fail()
        val accountId = state.value.selectedAccountId ?: return fail()
        val account = accountProvider(accountId) ?: return fail()
        val key = ListOperationKey(accountId, post.author.id, listId)
        listQueues.getOrPut(key, ::ArrayDeque).addLast(
            if (add) ListMembershipAction.ADD else ListMembershipAction.REMOVE,
        )
        if (!runningListActions.add(key)) return
        begin(key)
        scope.launch(ioDispatcher) {
            processListQueue(key, account, repository)
        }
    }

    fun clearFailure() {
        val accountId = state.value.selectedAccountId
        failedOperations.removeAll { it.accountId == accountId }
        publish()
    }

    fun accountChanged() = publish()

    private fun begin(key: MenuOperationKey) {
        pendingOperations += key
        failedOperations -= key
        publish()
    }

    private fun fail() {
        state.update { it.copy(postMenuActionPending = false, postMenuActionFailed = true) }
    }

    private suspend fun processListQueue(
        key: ListOperationKey,
        account: AccountSecrets,
        repository: ListMembershipExecutor,
    ) {
        var failed = false
        while (true) {
            val action = withContext(Dispatchers.Main.immediate) {
                val next = listQueues[key]?.removeFirstOrNull()
                if (next == null) {
                    listQueues.remove(key)
                    runningListActions.remove(key)
                    finish(key, failed)
                }
                next
            }
            if (action == null) return
            failed = runCatching {
                repository.execute(account, key.userId, key.listId, action, language())
            }.isFailure || failed
        }
    }

    private fun finish(key: MenuOperationKey, failed: Boolean) {
        pendingOperations -= key
        if (failed) failedOperations += key else failedOperations -= key
        publish()
    }

    private fun publish() {
        val accountId = state.value.selectedAccountId
        state.update {
            it.copy(
                postMenuActionPending = pendingOperations.any { key -> key.accountId == accountId },
                postMenuActionFailed = failedOperations.any { key -> key.accountId == accountId },
            )
        }
    }

    private fun language(): String = Locale.getDefault().toLanguageTag().ifBlank { "ja" }
}

private sealed interface MenuOperationKey {
    val accountId: String
}

private data class UserOperationKey(
    override val accountId: String,
    val userId: String,
    val action: UserAction,
) : MenuOperationKey

private data class ListOperationKey(
    override val accountId: String,
    val userId: String,
    val listId: String,
) : MenuOperationKey
