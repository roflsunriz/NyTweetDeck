package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.ListMembershipAction
import dev.nytweetdeck.android.data.ListMembershipRepository
import dev.nytweetdeck.android.data.UserAction
import dev.nytweetdeck.android.data.UserActionRepository
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
    private val userActions: UserActionRepository?,
    private val listMembership: ListMembershipRepository?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
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
        setPending()
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.execute(account, post.author.id, action, language())
            }
            finish(accountId, result.isFailure)
        }
    }

    fun listMembership(post: Post, listId: String, add: Boolean) {
        val repository = listMembership ?: return fail()
        val accountId = state.value.selectedAccountId ?: return fail()
        val account = accountProvider(accountId) ?: return fail()
        setPending()
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.execute(
                    account,
                    post.author.id,
                    listId,
                    if (add) ListMembershipAction.ADD else ListMembershipAction.REMOVE,
                    language(),
                )
            }
            finish(accountId, result.isFailure)
        }
    }

    fun clearFailure() {
        state.update { it.copy(postMenuActionFailed = false) }
    }

    private fun setPending() {
        state.update { it.copy(postMenuActionPending = true, postMenuActionFailed = false) }
    }

    private fun fail() {
        state.update { it.copy(postMenuActionPending = false, postMenuActionFailed = true) }
    }

    private suspend fun finish(accountId: String, failed: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            if (state.value.selectedAccountId == accountId) {
                state.update { it.copy(postMenuActionPending = false, postMenuActionFailed = failed) }
            }
        }
    }

    private fun language(): String = Locale.getDefault().toLanguageTag().ifBlank { "ja" }
}
