package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostDetailPage
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
    private val history = ArrayDeque<PostDetailUiState>()
    private val requestedReplyCursors = mutableMapOf<String, MutableSet<String>>()
    private var requestGeneration = 0L

    fun open(postId: String, knownPost: Post? = null) {
        val current = state.value.postDetail
        if (current.postId == postId && current.status != PostDetailStatus.CLOSED) return
        requestGeneration++
        if (current.status != PostDetailStatus.CLOSED) {
            if (history.size == MAX_DETAIL_HISTORY) history.removeFirst()
            history.addLast(current)
        }
        val initialPage = knownPost
            ?.takeIf { it.id == postId }
            ?.let {
                PostDetailPage(
                    post = it,
                    replies = emptyList(),
                    nextCursor = null,
                    rankingMode = state.value.replySort,
                )
            }
        state.update {
            it.copy(
                postDetail = if (initialPage == null) {
                    PostDetailUiState(PostDetailStatus.LOADING, postId)
                } else {
                    PostDetailUiState(
                        status = PostDetailStatus.READY,
                        postId = postId,
                        page = initialPage,
                        isLoadingMore = true,
                    )
                },
            )
        }
        requestedReplyCursors[postId] = mutableSetOf()
        load(postId, knownPost)
    }

    fun reload() {
        val detail = state.value.postDetail
        val postId = detail.postId ?: return
        requestGeneration++
        val knownPost = detail.page?.post
        requestedReplyCursors[postId] = mutableSetOf()
        state.update { current ->
            current.copy(
                postDetail = if (detail.page == null) {
                    PostDetailUiState(PostDetailStatus.LOADING, postId)
                } else {
                    detail.copy(
                        page = detail.page.copy(nextCursor = null),
                        isLoadingMore = true,
                        loadMoreFailed = false,
                    )
                },
            )
        }
        load(postId, knownPost)
    }

    fun loadMore() {
        val snapshot = state.value
        val detail = snapshot.postDetail
        val page = detail.page ?: return
        val cursor = page.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (detail.isLoadingMore) return
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        val requested = requestedReplyCursors.getOrPut(page.post.id, ::mutableSetOf)
        if (!requested.add(cursor)) {
            state.update { current ->
                current.copy(
                    postDetail = current.postDetail.copy(
                        page = current.postDetail.page?.copy(nextCursor = null),
                        isLoadingMore = false,
                    ),
                )
            }
            return
        }
        state.update { current ->
            current.copy(postDetail = detail.copy(isLoadingMore = true, loadMoreFailed = false))
        }
        val generation = requestGeneration
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.load(
                    account = account,
                    postId = page.post.id,
                    cursor = cursor,
                    knownFocalPost = page.post,
                    language = snapshot.appLanguageTag,
                    replySort = snapshot.replySort.name.lowercase(Locale.ROOT),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (requestGeneration != generation || state.value.selectedAccountId != accountId ||
                    state.value.postDetail.postId != page.post.id
                ) return@withContext
                state.update { current ->
                    val currentPage = current.postDetail.page ?: return@update current
                    result.fold(
                        onSuccess = { next ->
                            val merged = LinkedHashMap<String, dev.nytweetdeck.android.model.ConversationReply>()
                            currentPage.replies.forEach { merged[it.post.id] = it }
                            next.replies.forEach { merged.putIfAbsent(it.post.id, it) }
                            val nextCursor = next.nextCursor
                                ?.takeIf(String::isNotBlank)
                                ?.takeUnless(requested::contains)
                            current.copy(
                                postDetail = current.postDetail.copy(
                                    page = currentPage.copy(
                                        replies = merged.values.toList(),
                                        relatedPosts = (currentPage.relatedPosts + next.relatedPosts)
                                            .distinctBy { it.id }
                                            .filter { it.id !in merged && it.id != currentPage.post.id &&
                                                currentPage.contextPosts.none { context -> context.id == it.id } },
                                        nextCursor = nextCursor,
                                    ),
                                    isLoadingMore = false,
                                ),
                            )
                        },
                        onFailure = {
                            requested.remove(cursor)
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
        requestGeneration++
        val previous = history.removeLastOrNull()
        state.update { it.copy(postDetail = previous ?: PostDetailUiState()) }
        // A result for a hidden detail cannot update the active route. Restart any
        // interrupted request when returning to its saved page instead of restoring a spinner.
        if (previous?.status == PostDetailStatus.LOADING || previous?.isLoadingMore == true) {
            reload()
        }
    }

    fun reset() {
        requestGeneration++
        history.clear()
        requestedReplyCursors.clear()
        state.update { it.copy(postDetail = PostDetailUiState()) }
    }

    private fun load(postId: String, knownPost: Post? = null) {
        val snapshot = state.value
        val generation = requestGeneration
        val accountId = snapshot.selectedAccountId ?: return fail(postId)
        val account = accountProvider(accountId) ?: return fail(postId)
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.load(
                    account = account,
                    postId = postId,
                    knownFocalPost = knownPost,
                    language = snapshot.appLanguageTag,
                    replySort = snapshot.replySort.name.lowercase(Locale.ROOT),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (requestGeneration != generation || state.value.selectedAccountId != accountId ||
                    state.value.postDetail.postId != postId
                ) {
                    return@withContext
                }
                state.update { current ->
                    val currentDetail = current.postDetail
                    current.copy(
                        postDetail = result.fold(
                            onSuccess = { page -> PostDetailUiState(PostDetailStatus.READY, postId, page) },
                            onFailure = {
                                if (currentDetail.page == null) {
                                    PostDetailUiState(PostDetailStatus.FAILED, postId)
                                } else {
                                    currentDetail.copy(isLoadingMore = false, loadMoreFailed = true)
                                }
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun fail(postId: String) {
        state.update { it.copy(postDetail = PostDetailUiState(PostDetailStatus.FAILED, postId)) }
    }

    private companion object {
        const val MAX_DETAIL_HISTORY = 20
    }
}
