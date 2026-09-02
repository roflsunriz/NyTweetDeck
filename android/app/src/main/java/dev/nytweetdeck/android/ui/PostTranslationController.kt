package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.TranslationCandidate
import dev.nytweetdeck.android.model.PostTranslationException
import dev.nytweetdeck.android.model.PostTranslationResult
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationLoadStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PostTranslationController(
    private val repository: PostTranslationRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    init {
        // Provide an initial health snapshot so the UI does not stay in "unavailable" before any translation.
        state.update { current ->
            if (current.translationHealth == null) {
                current.copy(translationHealth = repository.health(current.selectedAccountId))
            } else current
        }
        // Keep health in sync when the selected account changes.
        scope.launch(Dispatchers.Main.immediate) {
            var lastAccountId: String? = state.value.selectedAccountId
            state.collect { snapshot ->
                val currentId = snapshot.selectedAccountId
                if (currentId != lastAccountId) {
                    lastAccountId = currentId
                    state.update { it.copy(translationHealth = repository.health(currentId)) }
                }
            }
        }
    }

    fun request(post: TranslationCandidate, manual: Boolean = false) {
        val snapshot = state.value
        if (!manual && !snapshot.autoTranslatePosts) return
        if (snapshot.postTranslations[post.postId]?.status == TranslationLoadStatus.LOADING) return
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        val targetLanguage = snapshot.translationLanguageTag
        state.update { current ->
            current.copy(
                postTranslations = current.postTranslations + (
                    post.postId to PostTranslationUiState(TranslationLoadStatus.LOADING)
                ),
            )
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.translate(
                    account = account,
                    postId = post.postId,
                    sourceLanguage = post.sourceLanguage,
                    targetLanguage = targetLanguage,
                    preTranslated = post.preTranslated,
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId) return@withContext
                state.update { current ->
                    val translated = result.fold(
                        onSuccess = { response ->
                            when (response) {
                                is PostTranslationResult.Translated -> PostTranslationUiState(
                                    TranslationLoadStatus.READY,
                                    response.translation,
                                )
                                is PostTranslationResult.Skipped -> PostTranslationUiState(
                                    TranslationLoadStatus.SKIPPED,
                                )
                            }
                        },
                        onFailure = { failure -> PostTranslationUiState(
                            status = TranslationLoadStatus.FAILED,
                            retryAfterSeconds = (failure as? PostTranslationException)?.retryAfterSeconds,
                        ) },
                    )
                    current.copy(
                        postTranslations = current.postTranslations + (post.postId to translated),
                        translationHealth = repository.health(accountId),
                    )
                }
            }
        }
    }

    fun toggleOriginal(postId: String) {
        state.update { current ->
            val translation = current.postTranslations[postId] ?: return@update current
            current.copy(
                postTranslations = current.postTranslations + (
                    postId to translation.copy(showOriginal = !translation.showOriginal)
                ),
            )
        }
    }
}
