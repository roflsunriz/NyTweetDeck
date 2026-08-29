package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.model.PostDetailUiState
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PostDetailController(
    private val repository: PostDetailRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    fun open(postId: String) {
        state.update {
            it.copy(postDetail = PostDetailUiState(PostDetailStatus.LOADING, postId))
        }
        load(postId)
    }

    fun reload() {
        val postId = state.value.postDetail.postId ?: return
        state.update {
            it.copy(postDetail = PostDetailUiState(PostDetailStatus.LOADING, postId))
        }
        load(postId)
    }

    fun loadMore() {
        val snapshot = state.value
        val detail = snapshot.postDetail
        val page = detail.page ?: return
        val cursor = page.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (detail.isLoadingMore) return
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        state.update { current ->
            current.copy(postDetail = detail.copy(isLoadingMore = true, loadMoreFailed = false))
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.load(
                    account = account,
                    postId = page.post.id,
                    cursor = cursor,
                    language = Locale.getDefault().toLanguageTag().ifBlank { "ja" },
                    replySort = snapshot.replySort.name.lowercase(Locale.ROOT),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId) return@withContext
                state.update { current ->
                    val currentPage = current.postDetail.page ?: return@update current
                    result.fold(
                        onSuccess = { next ->
                            val merged = LinkedHashMap<String, dev.nytweetdeck.android.model.ConversationReply>()
                            currentPage.replies.forEach { merged[it.post.id] = it }
                            next.replies.forEach { merged.putIfAbsent(it.post.id, it) }
                            current.copy(
                                postDetail = current.postDetail.copy(
                                    page = currentPage.copy(
                                        replies = merged.values.toList(),
                                        nextCursor = next.nextCursor,
                                    ),
                                    isLoadingMore = false,
                                ),
                            )
                        },
                        onFailure = {
                            current.copy(
                                postDetail = current.postDetail.copy(
                                    isLoadingMore = false,
                                    loadMoreFailed = true,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    fun toggleDeemphasizedReplies() {
        state.update { current ->
            current.copy(
                postDetail = current.postDetail.copy(
                    showDeemphasizedReplies = !current.postDetail.showDeemphasizedReplies,
                ),
            )
        }
    }

    fun close() {
        state.update { it.copy(postDetail = PostDetailUiState()) }
    }

    private fun load(postId: String) {
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return fail(postId)
        val account = accountProvider(accountId) ?: return fail(postId)
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.load(
                    account = account,
                    postId = postId,
                    language = Locale.getDefault().toLanguageTag().ifBlank { "ja" },
                    replySort = snapshot.replySort.name.lowercase(Locale.ROOT),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId || state.value.postDetail.postId != postId) {
                    return@withContext
                }
                state.update { current ->
                    current.copy(
                        postDetail = result.fold(
                            onSuccess = { page -> PostDetailUiState(PostDetailStatus.READY, postId, page) },
                            onFailure = { PostDetailUiState(PostDetailStatus.FAILED, postId) },
                        ),
                    )
                }
            }
        }
    }

    private fun fail(postId: String) {
        state.update { it.copy(postDetail = PostDetailUiState(PostDetailStatus.FAILED, postId)) }
    }
}
