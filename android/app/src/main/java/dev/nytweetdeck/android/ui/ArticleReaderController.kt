package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.ArticleReaderStatus
import dev.nytweetdeck.android.model.ArticleReaderUiState
import dev.nytweetdeck.android.model.DeckUiState
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ArticleReaderController(
    private val repository: PostDetailRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    fun open(postId: String, article: Article) {
        if (!article.body.isNullOrBlank()) {
            state.update {
                it.copy(articleReader = ArticleReaderUiState(ArticleReaderStatus.READY, postId, article))
            }
            return
        }
        state.update {
            it.copy(articleReader = ArticleReaderUiState(ArticleReaderStatus.LOADING, postId, article))
        }
        load(postId, article)
    }

    fun retry() {
        val reader = state.value.articleReader
        val postId = reader.postId ?: return
        val article = reader.article ?: return
        state.update { it.copy(articleReader = reader.copy(status = ArticleReaderStatus.LOADING)) }
        load(postId, article)
    }

    fun close() {
        state.update { it.copy(articleReader = ArticleReaderUiState()) }
    }

    private fun load(postId: String, initial: Article) {
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return fail(postId, initial)
        val account = accountProvider(accountId) ?: return fail(postId, initial)
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.load(
                    account,
                    postId,
                    language = Locale.getDefault().toLanguageTag().ifBlank { "ja" },
                    replySort = snapshot.replySort.name.lowercase(Locale.ROOT),
                ).post.article?.takeIf { it.id == initial.id && !it.body.isNullOrBlank() }
                    ?: error("記事全文がありません。")
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId || state.value.articleReader.postId != postId) {
                    return@withContext
                }
                state.update { current ->
                    current.copy(
                        articleReader = result.fold(
                            onSuccess = { ArticleReaderUiState(ArticleReaderStatus.READY, postId, it) },
                            onFailure = { ArticleReaderUiState(ArticleReaderStatus.FAILED, postId, initial) },
                        ),
                    )
                }
            }
        }
    }

    private fun fail(postId: String, article: Article) {
        state.update {
            it.copy(articleReader = ArticleReaderUiState(ArticleReaderStatus.FAILED, postId, article))
        }
    }
}
