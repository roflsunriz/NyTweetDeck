package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.TranslationCandidate
import dev.nytweetdeck.android.model.PostTranslationException
import dev.nytweetdeck.android.model.PostTranslationResult
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.model.Translation
import dev.nytweetdeck.android.text.hasTranslatableText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        if (!hasTranslatableText(post.text)) {
            state.update { current ->
                current.copy(postTranslations = current.postTranslations + (
                    post.postId to PostTranslationUiState(TranslationLoadStatus.SKIPPED)
                ))
            }
            return
        }
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
                if (state.value.selectedAccountId != accountId || state.value.translationLanguageTag != targetLanguage) return@withContext
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

    /**
     * 共有の翻訳版コピー用の本文を解決する。プリ翻訳→メモリ→ライブ翻訳の順で、
     * 取得できない場合は原文へ戻す。打ち切り後も裏で取得を続けてプールを温める。
     */
    suspend fun translatedBodyForShare(
        postId: String,
        text: String,
        sourceLanguage: String?,
        preTranslated: Translation?,
        timeoutMs: Long = 30_000L,
    ): String {
        val original = text.trim()
        if (!hasTranslatableText(text)) return original
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return original
        val account = accountProvider(accountId) ?: return original
        val targetLanguage = snapshot.translationLanguageTag
        return try {
            withTimeoutOrNull(timeoutMs) {
                withContext(NonCancellable + ioDispatcher) {
                    repository.translate(
                        account = account,
                        postId = postId,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        preTranslated = preTranslated,
                    )
                }
            }?.let { result ->
                when (result) {
                    is PostTranslationResult.Translated -> result.translation.text.ifBlank { original }
                    is PostTranslationResult.Skipped -> original
                }
            } ?: original
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            original
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
