package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.ListDirectoryRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.ListOption
import dev.nytweetdeck.android.model.ListPickerScope
import dev.nytweetdeck.android.model.ListPickerState
import dev.nytweetdeck.android.model.TimelineLoadStatus
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ListPickerController(
    private val repository: ListDirectoryRepository?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    private data class Cache(
        val mine: List<ListOption> = emptyList(),
        val suggested: List<ListOption> = emptyList(),
        val searches: Map<String, List<ListOption>> = emptyMap(),
        val lastSearchQuery: String = "",
    )

    private val caches = mutableMapOf<String, Cache>()
    private var directoryRefreshJob: Job? = null
    private var directoryRefreshAccountId: String? = null
    private var searchJob: Job? = null
    private var activeAccountId: String? = null

    fun accountChanged(accountId: String?) {
        activeAccountId = accountId
        searchJob?.cancel()
        val cached = accountId?.let(caches::get)
        updateState(
            ListPickerState(
                status = if (cached == null) TimelineLoadStatus.LOADING else TimelineLoadStatus.READY,
                mineOptions = cached?.mine.orEmpty(),
                suggestedOptions = cached?.suggested.orEmpty(),
                searchOptions = cached?.let { it.searches[it.lastSearchQuery] }.orEmpty(),
                searchQuery = cached?.lastSearchQuery.orEmpty(),
                isRefreshing = accountId != null,
            ),
        )
        if (accountId != null) refreshDirectories(accountId)
    }

    fun open() {
        val accountId = state.value.selectedAccountId ?: return
        activeAccountId = accountId
        val cached = caches[accountId]
        val current = state.value.listPicker
        updateState(
            current.copy(
                status = if (cached == null) TimelineLoadStatus.LOADING else TimelineLoadStatus.READY,
                selectedScope = ListPickerScope.MINE,
                mineOptions = cached?.mine ?: current.mineOptions,
                suggestedOptions = cached?.suggested ?: current.suggestedOptions,
                searchOptions = cached?.let { it.searches[it.lastSearchQuery] } ?: current.searchOptions,
                searchQuery = cached?.lastSearchQuery ?: current.searchQuery,
                isRefreshing = true,
                refreshFailed = false,
            ),
        )
        refreshDirectories(accountId)
    }

    fun selectScope(selectedScope: ListPickerScope) {
        updateState(state.value.listPicker.copy(selectedScope = selectedScope))
    }

    fun search(query: String) {
        val normalized = query.trim()
        require(normalized.isNotEmpty() && normalized.length <= 100) {
            "リスト検索語を入力してください。"
        }
        val accountId = state.value.selectedAccountId ?: return
        val cachedResults = caches[accountId]?.searches?.get(normalized)
        searchJob?.cancel()
        updateState(
            state.value.listPicker.copy(
                status = if (cachedResults == null) TimelineLoadStatus.LOADING else TimelineLoadStatus.READY,
                selectedScope = ListPickerScope.SEARCH,
                searchQuery = normalized,
                searchOptions = cachedResults.orEmpty(),
                isRefreshing = true,
                refreshFailed = false,
            ),
        )
        val account = accountProvider(accountId) ?: return markFailure()
        val listRepository = repository ?: return markFailure()
        searchJob = scope.launch(ioDispatcher) {
            val result = runCatching {
                listRepository.load(
                    account,
                    "search",
                    normalized,
                    language = Locale.getDefault().toLanguageTag().ifBlank { "ja" },
                ).lists.distinctBy(ListOption::id)
            }
            withContext(Dispatchers.Main.immediate) {
                if (activeAccountId != accountId || state.value.selectedAccountId != accountId) {
                    return@withContext
                }
                result.fold(
                    onSuccess = { options ->
                        val previous = caches[accountId] ?: Cache()
                        caches[accountId] = previous.copy(
                            searches = previous.searches + (normalized to options),
                            lastSearchQuery = normalized,
                        )
                        val current = state.value.listPicker
                        updateState(
                            current.copy(
                                status = TimelineLoadStatus.READY,
                                searchOptions = if (current.searchQuery == normalized) options else current.searchOptions,
                                isRefreshing = false,
                                refreshFailed = false,
                            ),
                        )
                    },
                    onFailure = { markFailure() },
                )
            }
        }
    }

    private fun refreshDirectories(accountId: String) {
        if (directoryRefreshJob?.isActive == true && directoryRefreshAccountId == accountId) return
        val account = accountProvider(accountId) ?: return markFailure()
        val listRepository = repository ?: return markFailure()
        directoryRefreshJob?.cancel()
        directoryRefreshAccountId = accountId
        directoryRefreshJob = scope.launch(ioDispatcher) {
            val mineResult = runCatching {
                listRepository.load(account, "mine").lists.distinctBy(ListOption::id)
            }
            val suggestedResult = runCatching {
                listRepository.load(account, "suggested").lists.distinctBy(ListOption::id)
            }
            withContext(Dispatchers.Main.immediate) {
                if (activeAccountId != accountId || state.value.selectedAccountId != accountId) {
                    return@withContext
                }
                val previous = caches[accountId] ?: Cache()
                val updated = previous.copy(
                    mine = mineResult.getOrNull() ?: previous.mine,
                    suggested = suggestedResult.getOrNull() ?: previous.suggested,
                )
                caches[accountId] = updated
                val failed = mineResult.isFailure || suggestedResult.isFailure
                updateState(
                    state.value.listPicker.copy(
                        status = if (updated.mine.isNotEmpty() || updated.suggested.isNotEmpty() || !failed) {
                            TimelineLoadStatus.READY
                        } else {
                            TimelineLoadStatus.FAILED
                        },
                        mineOptions = updated.mine,
                        suggestedOptions = updated.suggested,
                        isRefreshing = false,
                        refreshFailed = failed,
                    ),
                )
            }
        }
    }

    private fun markFailure() {
        val current = state.value.listPicker
        updateState(
            current.copy(
                status = if (current.visibleOptions.isEmpty()) {
                    TimelineLoadStatus.FAILED
                } else {
                    TimelineLoadStatus.READY
                },
                isRefreshing = false,
                refreshFailed = true,
            ),
        )
    }

    private fun updateState(value: ListPickerState) {
        state.update { current ->
            if (current.listPicker == value) current else current.copy(listPicker = value)
        }
    }
}
