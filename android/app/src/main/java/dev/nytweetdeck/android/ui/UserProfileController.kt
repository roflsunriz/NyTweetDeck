package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.UserDirectoryRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.UserProfileStatus
import dev.nytweetdeck.android.model.UserProfileTab
import dev.nytweetdeck.android.model.UserProfileUiState
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class UserProfileController(
    private val repository: UserDirectoryRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accountProvider: (String) -> AccountSecrets?,
    private val state: MutableStateFlow<DeckUiState>,
) {
    private val history = ArrayDeque<UserProfileUiState>()

    fun open(userId: String) {
        require(userId.matches(Regex("[0-9]{1,24}"))) { "XユーザーID形式が不正です。" }
        val current = state.value.userProfile
        if (current.userId == userId && current.status != UserProfileStatus.CLOSED) return
        if (current.status != UserProfileStatus.CLOSED) {
            if (history.size == MAX_HISTORY) history.removeFirst()
            history.addLast(current)
        }
        state.update {
            it.copy(userProfile = UserProfileUiState(UserProfileStatus.LOADING, userId))
        }
        load(userId, UserProfileTab.ALL, loadProfile = true)
    }

    fun selectTab(tab: UserProfileTab) {
        val current = state.value.userProfile
        val userId = current.userId ?: return
        if (current.tab == tab && current.status == UserProfileStatus.READY) return
        state.update {
            it.copy(
                userProfile = current.copy(
                    status = UserProfileStatus.LOADING,
                    tab = tab,
                    posts = emptyList(),
                    nextCursor = null,
                    loadMoreFailed = false,
                ),
            )
        }
        load(userId, tab, loadProfile = false)
    }

    fun retry() {
        val current = state.value.userProfile
        val userId = current.userId ?: return
        state.update { it.copy(userProfile = current.copy(status = UserProfileStatus.LOADING)) }
        load(userId, current.tab, loadProfile = current.profile == null)
    }

    fun loadMore() {
        val snapshot = state.value
        val current = snapshot.userProfile
        val userId = current.userId ?: return
        val cursor = current.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (current.isLoadingMore) return
        val accountId = snapshot.selectedAccountId ?: return
        val account = accountProvider(accountId) ?: return
        state.update {
            it.copy(userProfile = current.copy(isLoadingMore = true, loadMoreFailed = false))
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                repository.timeline(account, userId, current.tab, cursor, language())
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId ||
                    state.value.userProfile.userId != userId ||
                    state.value.userProfile.tab != current.tab
                ) return@withContext
                state.update { latest ->
                    val profile = latest.userProfile
                    result.fold(
                        onSuccess = { page ->
                            val posts = LinkedHashMap<String, dev.nytweetdeck.android.model.Post>()
                            profile.posts.forEach { posts[it.id] = it }
                            page.posts.forEach { posts.putIfAbsent(it.id, it) }
                            latest.copy(
                                userProfile = profile.copy(
                                    posts = posts.values.toList(),
                                    nextCursor = page.nextCursor,
                                    isLoadingMore = false,
                                ),
                            )
                        },
                        onFailure = {
                            latest.copy(
                                userProfile = profile.copy(
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

    fun close() {
        val previous = history.removeLastOrNull()
        state.update { it.copy(userProfile = previous ?: UserProfileUiState()) }
    }

    fun reset() {
        history.clear()
        state.update { it.copy(userProfile = UserProfileUiState()) }
    }

    private fun load(userId: String, tab: UserProfileTab, loadProfile: Boolean) {
        val snapshot = state.value
        val accountId = snapshot.selectedAccountId ?: return fail(userId, tab)
        val account = accountProvider(accountId) ?: return fail(userId, tab)
        val existingProfile = snapshot.userProfile.profile
        scope.launch(ioDispatcher) {
            val result = runCatching {
                val profile = if (loadProfile || existingProfile == null) {
                    repository.profile(account, userId, language())
                } else {
                    existingProfile
                }
                profile to repository.timeline(account, userId, tab, language = language())
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.value.selectedAccountId != accountId ||
                    state.value.userProfile.userId != userId ||
                    state.value.userProfile.tab != tab
                ) return@withContext
                state.update { current ->
                    current.copy(
                        userProfile = result.fold(
                            onSuccess = { (profile, page) ->
                                UserProfileUiState(
                                    status = UserProfileStatus.READY,
                                    userId = userId,
                                    profile = profile,
                                    tab = tab,
                                    posts = page.posts,
                                    nextCursor = page.nextCursor,
                                )
                            },
                            onFailure = {
                                current.userProfile.copy(status = UserProfileStatus.FAILED)
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun fail(userId: String, tab: UserProfileTab) {
        state.update {
            it.copy(
                userProfile = it.userProfile.copy(
                    status = UserProfileStatus.FAILED,
                    userId = userId,
                    tab = tab,
                ),
            )
        }
    }

    private fun language(): String = Locale.getDefault().toLanguageTag().ifBlank { "ja" }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
