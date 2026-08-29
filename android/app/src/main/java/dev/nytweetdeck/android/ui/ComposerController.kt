package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostComposerRepository
import dev.nytweetdeck.android.model.ComposerMode
import dev.nytweetdeck.android.model.ComposerStatus
import dev.nytweetdeck.android.model.ComposerUiState
import dev.nytweetdeck.android.model.DeckUiState
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ComposerController(
    private val repository: PostComposerRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    fun open(mode: ComposerMode, targetPostId: String? = null) {
        require((mode == ComposerMode.POST) == (targetPostId == null)) {
            "Composer対象の指定が不正です。"
        }
        state.update {
            it.copy(composer = ComposerUiState(mode = mode, targetPostId = targetPostId))
        }
    }

    fun close() {
        if (state.value.composer.status == ComposerStatus.SENDING) return
        state.update { it.copy(composer = ComposerUiState()) }
    }

    fun submit(text: String) {
        val snapshot = state.value
        if (snapshot.composer.status == ComposerStatus.SENDING) return
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        val composer = snapshot.composer
        state.update { it.copy(composer = composer.copy(status = ComposerStatus.SENDING)) }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.submit(
                    account = account,
                    text = text,
                    replyToPostId = composer.targetPostId.takeIf { composer.mode == ComposerMode.REPLY },
                    quotePostId = composer.targetPostId.takeIf { composer.mode == ComposerMode.QUOTE },
                    language = Locale.getDefault().toLanguageTag().ifBlank { "ja" },
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId) return@withContext
                state.update { current ->
                    current.copy(
                        composer = composer.copy(
                            status = if (result.isSuccess) {
                                ComposerStatus.SUCCEEDED
                            } else {
                                ComposerStatus.FAILED
                            },
                        ),
                    )
                }
            }
        }
    }
}
