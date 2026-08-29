package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.CommunityNoteRepository
import dev.nytweetdeck.android.model.CommunityNoteStatus
import dev.nytweetdeck.android.model.CommunityNoteUiState
import dev.nytweetdeck.android.model.DeckUiState
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CommunityNoteController(
    private val repository: CommunityNoteRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    fun open(noteId: String) {
        state.update { it.copy(communityNote = CommunityNoteUiState(CommunityNoteStatus.LOADING, noteId)) }
        load(noteId)
    }

    fun retry() {
        val noteId = state.value.communityNote.noteId ?: return
        state.update { it.copy(communityNote = CommunityNoteUiState(CommunityNoteStatus.LOADING, noteId)) }
        load(noteId)
    }

    fun close() {
        state.update { it.copy(communityNote = CommunityNoteUiState()) }
    }

    private fun load(noteId: String) {
        val accountId = state.value.selectedAccountId ?: return fail(noteId)
        val account = accountProvider(accountId) ?: return fail(noteId)
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.load(
                    account,
                    noteId,
                    Locale.getDefault().toLanguageTag().ifBlank { "ja" },
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId || state.value.communityNote.noteId != noteId) {
                    return@withContext
                }
                state.update { current ->
                    current.copy(
                        communityNote = result.fold(
                            onSuccess = { CommunityNoteUiState(CommunityNoteStatus.READY, noteId, it) },
                            onFailure = { CommunityNoteUiState(CommunityNoteStatus.FAILED, noteId) },
                        ),
                    )
                }
            }
        }
    }

    private fun fail(noteId: String) {
        state.update { it.copy(communityNote = CommunityNoteUiState(CommunityNoteStatus.FAILED, noteId)) }
    }
}
