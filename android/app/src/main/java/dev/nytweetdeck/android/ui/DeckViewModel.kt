package dev.nytweetdeck.android.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.data.NotificationRepository
import dev.nytweetdeck.android.data.TrendRepository
import dev.nytweetdeck.android.data.DirectMessageRepository
import dev.nytweetdeck.android.data.ListDirectoryRepository
import dev.nytweetdeck.android.data.UserDirectoryRepository
import dev.nytweetdeck.android.data.PostActionRepository
import dev.nytweetdeck.android.data.PostComposerRepository
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.data.CommunityNoteRepository
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.data.UserActionRepository
import dev.nytweetdeck.android.data.ListMembershipRepository
import dev.nytweetdeck.android.data.UserAction
import dev.nytweetdeck.android.xapi.live.LivePipelineSubscriptionService
import dev.nytweetdeck.android.xapi.XApiMetadataRefresher
import dev.nytweetdeck.android.data.LayoutTransfer
import dev.nytweetdeck.android.model.AccountAuthStatus
import dev.nytweetdeck.android.model.AccountUiModel
import dev.nytweetdeck.android.model.ColumnTimelineState
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.model.NotificationColumnState
import dev.nytweetdeck.android.model.TrendColumnState
import dev.nytweetdeck.android.model.DirectMessageColumnState
import dev.nytweetdeck.android.model.ColumnScrollPosition
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.model.ComposerMode
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.model.DisplaySettings
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.TranslationCandidate
import dev.nytweetdeck.android.model.TargetPickerState
import dev.nytweetdeck.android.model.ListOption
import dev.nytweetdeck.android.model.ListPickerScope
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.CapturedWebSession
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.ColumnSort
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import java.util.UUID
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dev.nytweetdeck.android.xapi.XSessionVerifier
class DeckViewModel(
    private val settingsStore: DeckSettingsStore? = null,
    private val accountStoreFile: File? = null,
    private val sessionVerifier: XSessionVerifier? = null,
    private val timelineRepository: TimelineRepository? = null,
    private val notificationRepository: NotificationRepository? = null,
    private val trendRepository: TrendRepository? = null,
    private val directMessageRepository: DirectMessageRepository? = null,
    private val listDirectoryRepository: ListDirectoryRepository? = null,
    private val userDirectoryRepository: UserDirectoryRepository? = null,
    private val postActionRepository: PostActionRepository? = null,
    private val postComposerRepository: PostComposerRepository? = null,
    private val postDetailRepository: PostDetailRepository? = null,
    private val communityNoteRepository: CommunityNoteRepository? = null,
    private val postTranslationRepository: PostTranslationRepository? = null,
    private val userActionRepository: UserActionRepository? = null,
    private val listMembershipRepository: ListMembershipRepository? = null,
    private val livePipelineService: LivePipelineSubscriptionService? = null,
    metadataRefresher: XApiMetadataRefresher? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val adaptiveRefreshIntervalMillis: Long? = null,
    private val visibilityRefreshDelayMillis: Long = DEFAULT_VISIBILITY_REFRESH_DELAY_MILLIS,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        DeckUiState(isInitializing = settingsStore != null),
    )
    val state: StateFlow<DeckUiState> = mutableState.asStateFlow()
    private val saveRequests = Channel<DeckUiState>(capacity = Channel.CONFLATED)
    @Volatile
    private var accountStore: AccountStore? = null
    private var visibleColumnIds: Set<String> = emptySet()
    private val visibilityRefreshJobs = mutableMapOf<String, Job>()
    private val accountColumnCaches = mutableMapOf<String, AccountColumnSnapshot>()
    private val postActionController = postActionRepository?.let { repository ->
        PostActionController(
            repository = repository,
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            accountProvider = ::savedAccount,
            state = mutableState,
        )
    }
    private val composerController = postComposerRepository?.let { repository ->
        ComposerController(
            repository = repository,
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            accountProvider = ::savedAccount,
            state = mutableState,
        )
    }
    private val postDetailController = postDetailRepository?.let { repository ->
        PostDetailController(
            repository = repository,
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            accountProvider = ::savedAccount,
            state = mutableState,
        )
    }
    private val articleReaderController = postDetailRepository?.let { repository ->
        ArticleReaderController(repository, viewModelScope, ioDispatcher, ::savedAccount, mutableState)
    }
    private val communityNoteController = communityNoteRepository?.let { repository ->
        CommunityNoteController(repository, viewModelScope, ioDispatcher, ::savedAccount, mutableState)
    }
    private val postTranslationController = postTranslationRepository?.let { repository ->
        PostTranslationController(repository, viewModelScope, ioDispatcher, ::savedAccount, mutableState)
    }
    private val postMenuController = PostMenuController(
        userActionRepository,
        listMembershipRepository,
        viewModelScope,
        ioDispatcher,
        ::savedAccount,
        mutableState,
    )
    private val liveDeckController = LiveDeckController(
        livePipelineService,
        viewModelScope,
        ::savedAccount,
        mutableState,
        ::refreshColumn,
    )
    private val metadataRefreshController = MetadataRefreshController(
        metadataRefresher, viewModelScope, ioDispatcher, mutableState,
    )
    private val columnPagingController = ColumnPagingController(
        timelineRepository,
        notificationRepository,
        trendRepository,
        directMessageRepository,
        viewModelScope,
        ioDispatcher,
        ::savedAccount,
        mutableState,
    )
    private val listPickerController = ListPickerController(
        listDirectoryRepository,
        viewModelScope,
        ioDispatcher,
        ::savedAccount,
        mutableState,
    )
    internal val userProfileController = userDirectoryRepository?.let { repository ->
        UserProfileController(
            repository,
            viewModelScope,
            ioDispatcher,
            ::savedAccount,
            mutableState,
        )
    }
    @Volatile
    private var foreground = false
    @Volatile
    private var currentAdaptiveDelayMillis = adaptiveRefreshIntervalMillis ?: 0L
    private fun savedAccount(accountId: String): AccountSecrets? =
        accountStore?.let { runCatching { it.requireAccount(accountId) }.getOrNull() }
    init {
        require(visibilityRefreshDelayMillis >= 0L) { "表示後更新の待機時間が不正です。" }
        metadataRefreshController.refresh(force = true)
        if (adaptiveRefreshIntervalMillis != null) {
            require(adaptiveRefreshIntervalMillis >= 15_000L) { "適応更新間隔が短すぎます。" }
            viewModelScope.launch(ioDispatcher) {
                while (isActive) {
                    delay(currentAdaptiveDelayMillis)
                    if (foreground && mutableState.value.autoRefreshTimelines && visibleColumnIds.isNotEmpty()) {
                        withContext(Dispatchers.Main.immediate) { refreshVisibleColumns() }
                    }
                }
            }
        }
        if (settingsStore != null) {
            viewModelScope.launch(ioDispatcher) {
                for (state in saveRequests) {
                    try {
                        settingsStore.save(state.copy(isInitializing = false, settingsConflict = false))
                    } catch (_: DeckSettingsStore.DeckSettingsConflictException) {
                        withContext(Dispatchers.Main.immediate) {
                            mutableState.update { it.copy(settingsConflict = true) }
                        }
                    }
                }
            }
            viewModelScope.launch(ioDispatcher) {
                val loadedLayout = settingsStore.load()
                val loadedAccountStore = accountStoreFile?.let(::AccountStore)
                accountStore = loadedAccountStore
                val selectedAccount = loadedAccountStore?.selectedAccount()
                val cachedTimelines = if (selectedAccount != null && timelineRepository != null) {
                    loadedLayout.columns.mapNotNull { column ->
                        val kind = queryKind(column.kind) ?: return@mapNotNull null
                        timelineRepository.cached(selectedAccount, kind, column.target)?.let { page ->
                            column.id to ColumnTimelineState(
                                status = TimelineLoadStatus.READY,
                                posts = page.posts,
                                nextCursor = page.nextCursor,
                            )
                        }
                    }.toMap()
                } else {
                    emptyMap()
                }
                val loaded = loadedLayout.copy(
                    isInitializing = false,
                    accounts = loadedAccountStore?.accountSummaries()?.map { summary ->
                        AccountUiModel(
                            summary.accountId,
                            summary.userId,
                            summary.username,
                            summary.displayName,
                        )
                    }.orEmpty(),
                    selectedAccountId = loadedAccountStore?.selectedAccountId(),
                    timelines = cachedTimelines,
                )
                withContext(Dispatchers.Main.immediate) {
                    loaded.selectedAccountId?.let { selectedId ->
                        accountColumnCaches[selectedId] = AccountColumnSnapshot(
                            timelines = loaded.timelines,
                        )
                    }
                    val runtime = mutableState.value
                    mutableState.value = loaded.copy(
                        xApiMetadataRefreshing = runtime.xApiMetadataRefreshing,
                        xApiMetadataError = runtime.xApiMetadataError,
                        xApiMetadataLastSuccessAt = runtime.xApiMetadataLastSuccessAt,
                        xApiMetadataSourceVersion = runtime.xApiMetadataSourceVersion,
                    )
                    listPickerController.accountChanged(loaded.selectedAccountId)
                }
            }
        }
    }

    fun selectMenu(kind: ColumnKind) {
        mutate { it.copy(selectedMenu = kind) }
    }

    fun addColumn(kind: ColumnKind, title: String, target: String? = null): String? {
        var addedId: String? = null
        mutate { current ->
            val id = UUID.randomUUID().toString()
            addedId = id
            current.copy(
                columns = current.columns + DeckColumn(
                    id = id,
                    kind = kind,
                    title = title,
                    target = target,
                ),
                selectedMenu = kind,
            )
        }
        return addedId
    }

    fun removeColumn(id: String) {
        visibilityRefreshJobs.remove(id)?.cancel()
        visibleColumnIds = visibleColumnIds - id
        mutate { current ->
            current.copy(
                columns = current.columns.filterNot { it.id == id },
                timelines = current.timelines - id,
                notifications = current.notifications - id,
                trends = current.trends - id,
                messages = current.messages - id,
                columnScrollPositions = current.columnScrollPositions - id,
            )
        }
    }

    fun moveColumn(id: String, direction: Int) {
        if (direction == 0) return
        mutate { current ->
            val from = current.columns.indexOfFirst { it.id == id }
            if (from < 0) return@mutate current
            val to = (from + direction.coerceIn(-1, 1)).coerceIn(current.columns.indices)
            if (from == to) return@mutate current
            val reordered = current.columns.toMutableList()
            val column = reordered.removeAt(from)
            reordered.add(to, column)
            current.copy(columns = reordered)
        }
    }

    fun toggleMainMenuItem(item: MainMenuItemId) {
        mutate { current ->
            val updated = if (item in current.mainMenuItems) {
                current.mainMenuItems - item
            } else {
                current.mainMenuItems + item
            }
            current.copy(mainMenuItems = updated)
        }
    }

    fun moveMainMenuItem(item: MainMenuItemId, direction: Int) {
        if (direction == 0) return
        mutate { current ->
            val from = current.mainMenuItems.indexOf(item)
            if (from < 0) return@mutate current
            val to = (from + direction.coerceIn(-1, 1)).coerceIn(current.mainMenuItems.indices)
            if (to == from) return@mutate current
            val reordered = current.mainMenuItems.toMutableList()
            reordered.removeAt(from)
            reordered.add(to, item)
            current.copy(mainMenuItems = reordered)
        }
    }

    fun setDarkTheme(enabled: Boolean) = setDisplaySettings(mutableState.value.displaySettings().copy(themeMode = if (enabled) dev.nytweetdeck.android.model.ThemeMode.DARK else dev.nytweetdeck.android.model.ThemeMode.LIGHT))
    fun setCompactDensity(enabled: Boolean) = setDisplaySettings(mutableState.value.displaySettings().copy(compactDensity = enabled))
    fun setDisplaySettings(settings: DisplaySettings) = mutate { it.withDisplaySettings(settings) }
    fun openTrendSearch(query: String) = mutate { it.withTrendSearch(query, addColumn = true) }
    fun recordTrendSearch(query: String) = mutate { it.withTrendSearch(query, addColumn = false) }
    fun clearTrendSearchHistory() = mutate { it.copy(trendSearchHistory = emptyList()) }
    fun setAppLanguage(languageTag: String) = mutate { it.copy(appLanguageTag = languageTag) }
    fun setTranslationLanguage(languageTag: String) = mutate {
        it.copy(translationLanguageTag = languageTag, postTranslations = emptyMap())
    }

    fun setColumnSort(columnId: String, sort: ColumnSort) {
        if (mutableState.value.columns.none { it.id == columnId && it.sort != sort }) return
        mutate { current ->
            current.copy(
                columns = current.columns.map { column ->
                    if (column.id == columnId) column.copy(sort = sort) else column
                },
            )
        }
        refreshColumn(columnId)
    }

    fun importLayout(serialized: ByteArray) {
        val current = mutableState.value
        if (current.isInitializing) return
        val imported = LayoutTransfer.importSettings(serialized, current).state
        mutate { imported }
        refreshVisibleColumns()
    }

    fun acceptCapturedSession(session: CapturedWebSession) {
        val verifier = sessionVerifier ?: return
        if (mutableState.value.accountAuthStatus == AccountAuthStatus.VERIFYING) return
        mutableState.update { it.copy(accountAuthStatus = AccountAuthStatus.VERIFYING) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val verified = verifier.verify(session)
                val store = accountStore ?: accountStoreFile?.let(::AccountStore)?.also {
                    accountStore = it
                } ?: error("アカウント保存先がありません。")
                store.addOrReplace(
                    AccountSecrets(
                        accountId = verified.account.userId,
                        userId = verified.account.userId,
                        username = verified.account.username,
                        displayName = verified.account.displayName,
                        webBearerToken = verified.credentials.bearerToken,
                        authToken = verified.credentials.authToken,
                        csrfToken = verified.credentials.csrfToken,
                        profileName = verified.profileName,
                    ),
                    select = true,
                )
                val accounts = store.accountSummaries().map { summary ->
                    AccountUiModel(
                        summary.accountId,
                        summary.userId,
                        summary.username,
                        summary.displayName,
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    postDetailController?.reset()
                    userProfileController?.reset()
                    rememberSelectedAccountColumns()
                    val selectedId = store.selectedAccountId()
                    val restored = selectedId?.let(accountColumnCaches::get) ?: AccountColumnSnapshot()
                    mutableState.update {
                        it.copy(
                            accounts = accounts,
                            selectedAccountId = selectedId,
                            postTranslations = emptyMap(),
                            accountAuthStatus = AccountAuthStatus.IDLE,
                            timelines = restored.timelines,
                            notifications = restored.notifications,
                            trends = restored.trends,
                            messages = restored.messages,
                        )
                    }
                    postMenuController.accountChanged()
                    listPickerController.accountChanged(selectedId)
                    refreshVisibleColumns()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    mutableState.update { it.copy(accountAuthStatus = AccountAuthStatus.FAILED) }
                }
            }
        }
    }

    fun selectAccount(accountId: String) {
        val store = accountStore ?: return
        if (mutableState.value.selectedAccountId == accountId) return
        rememberSelectedAccountColumns()
        val retainedSnapshot = accountColumnCaches[accountId]
        viewModelScope.launch(ioDispatcher) {
            runCatching { store.selectAccount(accountId) }
                .onSuccess {
                    val account = runCatching { store.requireAccount(accountId) }.getOrNull()
                    val diskTimelines = if (
                        retainedSnapshot == null && account != null && timelineRepository != null
                    ) {
                        mutableState.value.columns.mapNotNull { column ->
                            val kind = queryKind(column.kind) ?: return@mapNotNull null
                            timelineRepository.cached(account, kind, column.target)?.let { page ->
                                column.id to ColumnTimelineState(
                                    status = TimelineLoadStatus.READY,
                                    posts = page.posts,
                                    nextCursor = page.nextCursor,
                                )
                            }
                        }.toMap()
                    } else {
                        emptyMap()
                    }
                    withContext(Dispatchers.Main.immediate) {
                        postDetailController?.reset()
                        userProfileController?.reset()
                        val restored = retainedSnapshot ?: AccountColumnSnapshot(timelines = diskTimelines)
                        mutableState.update {
                            it.copy(
                                selectedAccountId = accountId,
                                postTranslations = emptyMap(),
                                timelines = restored.timelines,
                                notifications = restored.notifications,
                                trends = restored.trends,
                                messages = restored.messages,
                            )
                        }
                        postMenuController.accountChanged()
                        listPickerController.accountChanged(accountId)
                        accountColumnCaches[accountId] = restored
                        refreshVisibleColumns()
                    }
                }
        }
    }

    fun refreshColumn(columnId: String) {
        val store = accountStore ?: return
        val snapshot = mutableState.value
        val accountId = snapshot.selectedAccountId ?: return
        val account = runCatching { store.requireAccount(accountId) }.getOrNull() ?: return
        val column = snapshot.columns.firstOrNull { it.id == columnId } ?: return
        when (column.kind) {
            ColumnKind.NOTIFICATIONS -> {
                refreshNotifications(columnId, accountId, account)
                return
            }
            ColumnKind.TRENDS -> {
                refreshTrends(columnId, accountId, account)
                return
            }
            ColumnKind.MESSAGES -> {
                refreshMessages(columnId, accountId, account)
                return
            }
            else -> Unit
        }
        val repository = timelineRepository ?: return
        val queryKind = queryKind(column.kind) ?: return
        val previousTimeline = snapshot.timelines[columnId]
        if (previousTimeline?.status == TimelineLoadStatus.LOADING || previousTimeline?.isRefreshing == true) return
        mutableState.update { current ->
            current.copy(
                timelines = current.timelines + (
                    columnId to if (previousTimeline?.status == TimelineLoadStatus.READY) {
                        previousTimeline.copy(isRefreshing = true, refreshFailed = false)
                    } else {
                        ColumnTimelineState(status = TimelineLoadStatus.LOADING)
                    }
                ),
            )
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val page = repository.load(
                    account = account,
                    kind = queryKind,
                    target = column.target,
                    language = snapshot.appLanguageTag,
                    sort = column.sort.name.lowercase(Locale.ROOT),
                )
                withContext(Dispatchers.Main.immediate) {
                    if (mutableState.value.selectedAccountId != accountId) return@withContext
                    mutableState.update { current ->
                        val existing = current.timelines[columnId]
                        val existingIds = existing?.posts?.mapTo(HashSet()) { it.id }.orEmpty()
                        val newPosts = if (existing?.status == TimelineLoadStatus.READY) {
                            page.posts.filterNot { it.id in existingIds }
                        } else {
                            emptyList()
                        }
                        val merged = LinkedHashMap<String, dev.nytweetdeck.android.model.Post>()
                        page.posts.forEach { merged[it.id] = it }
                        existing?.posts?.forEach { merged.putIfAbsent(it.id, it) }
                        if (adaptiveRefreshIntervalMillis != null && existing?.status == TimelineLoadStatus.READY) {
                            currentAdaptiveDelayMillis = if (newPosts.isEmpty()) {
                                (currentAdaptiveDelayMillis * 2).coerceAtMost(MAX_ADAPTIVE_DELAY_MILLIS)
                            } else {
                                adaptiveRefreshIntervalMillis
                            }
                        }
                        current.copy(
                            timelines = current.timelines + (
                                columnId to ColumnTimelineState(
                                    status = TimelineLoadStatus.READY,
                                    posts = merged.values.toList(),
                                    nextCursor = existing?.nextCursor ?: page.nextCursor,
                                    isRefreshing = false,
                                    refreshFailed = false,
                                    newPostCount = (existing?.newPostCount ?: 0) + newPosts.size,
                                    newPostAvatarUrls = (
                                        newPosts.mapNotNull { it.author.avatarUrl } +
                                            existing?.newPostAvatarUrls.orEmpty()
                                        )
                                        .distinct()
                                        .take(5),
                                    refreshGeneration = if (existing?.status == TimelineLoadStatus.READY) {
                                        existing.refreshGeneration + 1
                                    } else {
                                        existing?.refreshGeneration ?: 0
                                    },
                                )
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    mutableState.update { current ->
                        val existing = current.timelines[columnId]
                        current.copy(
                            timelines = current.timelines + (
                                columnId to if (existing?.posts?.isNotEmpty() == true) {
                                    existing.copy(
                                        status = TimelineLoadStatus.READY,
                                        isRefreshing = false,
                                        refreshFailed = true,
                                        refreshGeneration = existing.refreshGeneration + 1,
                                    )
                                } else {
                                    ColumnTimelineState(status = TimelineLoadStatus.FAILED)
                                }
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun refreshNotifications(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = notificationRepository ?: return
        val previous = mutableState.value.notifications[columnId]
        if (previous?.status == TimelineLoadStatus.LOADING || previous?.isRefreshing == true) return
        mutableState.update { current ->
            current.copy(
                notifications = current.notifications + (
                    columnId to if (previous?.status == TimelineLoadStatus.READY) {
                        previous.copy(isRefreshing = true, refreshFailed = false)
                    } else {
                        NotificationColumnState(status = TimelineLoadStatus.LOADING)
                    }
                ),
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                    repository.load(account, language = mutableState.value.appLanguageTag)
            }
            withContext(Dispatchers.Main.immediate) {
                if (mutableState.value.selectedAccountId != accountId) return@withContext
                mutableState.update { current ->
                    val existing = current.notifications[columnId]
                    current.copy(
                        notifications = current.notifications + (
                            columnId to result.fold(
                                onSuccess = { page ->
                                    val existingIds = existing?.page?.notifications
                                        ?.mapTo(HashSet()) { it.id }
                                        .orEmpty()
                                    val newItems = if (existing?.status == TimelineLoadStatus.READY) {
                                        page.notifications.filterNot { it.id in existingIds }
                                    } else {
                                        emptyList()
                                    }
                                    val mergedNotifications = LinkedHashMap<String, dev.nytweetdeck.android.model.Notification>()
                                    page.notifications.forEach { mergedNotifications[it.id] = it }
                                    existing?.page?.notifications?.forEach { mergedNotifications.putIfAbsent(it.id, it) }
                                    val mergedPosts = LinkedHashMap<String, dev.nytweetdeck.android.model.Post>()
                                    page.posts.forEach { mergedPosts[it.id] = it }
                                    existing?.page?.posts?.forEach { mergedPosts.putIfAbsent(it.id, it) }
                                    NotificationColumnState(
                                        status = TimelineLoadStatus.READY,
                                        page = page.copy(
                                            notifications = mergedNotifications.values.toList(),
                                            posts = mergedPosts.values.toList(),
                                            nextCursor = existing?.page?.nextCursor ?: page.nextCursor,
                                        ),
                                        newItemCount = newItems.size,
                                        newItemAvatarUrls = newItems.flatMap { item ->
                                            item.actors.mapNotNull { it.avatarUrl }
                                        }.distinct().take(5),
                                        refreshGeneration = if (existing?.status == TimelineLoadStatus.READY) {
                                            existing.refreshGeneration + 1
                                        } else {
                                            existing?.refreshGeneration ?: 0
                                        },
                                    )
                                },
                                onFailure = {
                                    if (existing?.page != null) {
                                        existing.copy(
                                            status = TimelineLoadStatus.READY,
                                            isRefreshing = false,
                                            refreshFailed = true,
                                            refreshGeneration = existing.refreshGeneration + 1,
                                        )
                                    } else {
                                        NotificationColumnState(status = TimelineLoadStatus.FAILED)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }
    }

    private fun refreshTrends(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = trendRepository ?: return
        val previous = mutableState.value.trends[columnId]
        if (previous?.status == TimelineLoadStatus.LOADING || previous?.isRefreshing == true) return
        mutableState.update { current ->
            current.copy(
                trends = current.trends + (
                    columnId to if (previous?.status == TimelineLoadStatus.READY) {
                        previous.copy(isRefreshing = true, refreshFailed = false)
                    } else {
                        TrendColumnState(status = TimelineLoadStatus.LOADING)
                    }
                ),
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                    repository.load(account, language = mutableState.value.appLanguageTag)
            }
            withContext(Dispatchers.Main.immediate) {
                if (mutableState.value.selectedAccountId != accountId) return@withContext
                mutableState.update { current ->
                    val existing = current.trends[columnId]
                    current.copy(
                        trends = current.trends + (
                            columnId to result.fold(
                                onSuccess = { page ->
                                    val existingKeys = existing?.page?.trends
                                        ?.mapTo(HashSet()) { it.url }
                                        .orEmpty()
                                    val newCount = if (existing?.status == TimelineLoadStatus.READY) {
                                        page.trends.count { it.url !in existingKeys }
                                    } else {
                                        0
                                    }
                                    TrendColumnState(
                                        status = TimelineLoadStatus.READY,
                                        page = page,
                                        newItemCount = newCount,
                                        refreshGeneration = if (existing?.status == TimelineLoadStatus.READY) {
                                            existing.refreshGeneration + 1
                                        } else {
                                            existing?.refreshGeneration ?: 0
                                        },
                                    )
                                },
                                onFailure = {
                                    if (existing?.page != null) {
                                        existing.copy(
                                            status = TimelineLoadStatus.READY,
                                            isRefreshing = false,
                                            refreshFailed = true,
                                            refreshGeneration = existing.refreshGeneration + 1,
                                        )
                                    } else {
                                        TrendColumnState(status = TimelineLoadStatus.FAILED)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }
    }

    private fun refreshMessages(columnId: String, accountId: String, account: AccountSecrets) {
        val repository = directMessageRepository ?: return
        val previous = mutableState.value.messages[columnId]
        if (previous?.status == TimelineLoadStatus.LOADING || previous?.isRefreshing == true) return
        mutableState.update { current ->
            current.copy(
                messages = current.messages + (
                    columnId to if (previous?.status == TimelineLoadStatus.READY) {
                        previous.copy(isRefreshing = true, refreshFailed = false)
                    } else {
                        DirectMessageColumnState(status = TimelineLoadStatus.LOADING)
                    }
                ),
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                    repository.load(account, language = mutableState.value.appLanguageTag)
            }
            withContext(Dispatchers.Main.immediate) {
                if (mutableState.value.selectedAccountId != accountId) return@withContext
                mutableState.update { current ->
                    val existing = current.messages[columnId]
                    current.copy(
                        messages = current.messages + (
                            columnId to result.fold(
                                onSuccess = { page ->
                                    val existingIds = existing?.page?.messages
                                        ?.mapTo(HashSet()) { it.id }
                                        .orEmpty()
                                    val newItems = if (existing?.status == TimelineLoadStatus.READY) {
                                        page.messages.filterNot { it.id in existingIds }
                                    } else {
                                        emptyList()
                                    }
                                    val merged = LinkedHashMap<String, dev.nytweetdeck.android.model.DirectMessage>()
                                    page.messages.forEach { merged[it.id] = it }
                                    existing?.page?.messages?.forEach { merged.putIfAbsent(it.id, it) }
                                    DirectMessageColumnState(
                                        status = TimelineLoadStatus.READY,
                                        page = page.copy(
                                            messages = merged.values.toList(),
                                            nextCursor = existing?.page?.nextCursor ?: page.nextCursor,
                                        ),
                                        newItemCount = newItems.size,
                                        newItemAvatarUrls = newItems.mapNotNull { it.senderAvatarUrl }
                                            .distinct()
                                            .take(5),
                                        refreshGeneration = if (existing?.status == TimelineLoadStatus.READY) {
                                            existing.refreshGeneration + 1
                                        } else {
                                            existing?.refreshGeneration ?: 0
                                        },
                                    )
                                },
                                onFailure = {
                                    if (existing?.page != null) {
                                        existing.copy(
                                            status = TimelineLoadStatus.READY,
                                            isRefreshing = false,
                                            refreshFailed = true,
                                            refreshGeneration = existing.refreshGeneration + 1,
                                        )
                                    } else {
                                        DirectMessageColumnState(status = TimelineLoadStatus.FAILED)
                                    }
                                },
                            )
                        ),
                    )
                }
            }
        }
    }

    fun loadMore(columnId: String) = columnPagingController.loadMore(columnId)
    fun clearNewPosts(columnId: String) {
        mutableState.update { current ->
            current.copy(
                timelines = current.timelines[columnId]?.let { timeline ->
                    current.timelines + (
                        columnId to timeline.copy(newPostCount = 0, newPostAvatarUrls = emptyList())
                    )
                } ?: current.timelines,
                notifications = current.notifications[columnId]?.let { notifications ->
                    current.notifications + (
                        columnId to notifications.copy(newItemCount = 0, newItemAvatarUrls = emptyList())
                    )
                } ?: current.notifications,
                trends = current.trends[columnId]?.let { trends ->
                    current.trends + (columnId to trends.copy(newItemCount = 0))
                } ?: current.trends,
                messages = current.messages[columnId]?.let { messages ->
                    current.messages + (
                        columnId to messages.copy(newItemCount = 0, newItemAvatarUrls = emptyList())
                    )
                } ?: current.messages,
            )
        }
    }
    fun togglePostAction(postId: String, action: PostActionType) {
        postActionController?.toggle(postId, action)
    }
    fun clearPostActionFailure(postId: String, action: PostActionType) {
        postActionController?.clearFailure(postId, action)
    }
    fun openComposer(mode: ComposerMode, targetPostId: String? = null) {
        composerController?.open(mode, targetPostId)
    }
    fun closeComposer() {
        composerController?.close()
    }
    fun submitPost(text: String) {
        composerController?.submit(text)
    }
    fun openPostDetail(postId: String) {
        postDetailController?.open(postId, findLoadedPost(postId))
    }
    fun closePostDetail() {
        postDetailController?.close()
    }
    fun retryPostDetail() {
        postDetailController?.reload()
    }
    fun loadMorePostDetail() {
        postDetailController?.loadMore()
    }
    fun toggleDeemphasizedReplies() {
        postDetailController?.toggleDeemphasizedReplies()
    }

    fun setReplySort(sort: RankingMode) {
        mutate { it.copy(replySort = sort) }
        postDetailController?.reload()
    }
    fun openArticle(postId: String, article: Article) = articleReaderController?.open(postId, article)
    fun retryArticle() = articleReaderController?.retry()
    fun closeArticle() = articleReaderController?.close()
    fun openCommunityNote(noteId: String) = communityNoteController?.open(noteId)
    fun retryCommunityNote() = communityNoteController?.retry()
    fun closeCommunityNote() = communityNoteController?.close()
    fun requestPostTranslation(post: TranslationCandidate) {
        if (post.communityNote != null) communityNoteController?.translate(post) else postTranslationController?.request(post)
    }
    fun retryPostTranslation(post: TranslationCandidate) {
        if (post.communityNote != null) communityNoteController?.translate(post, manual = true) else postTranslationController?.request(post, manual = true)
    }
    fun togglePostOriginal(postId: String) = postTranslationController?.toggleOriginal(postId)
    fun hidePost(postId: String) = postMenuController.hide(postId)
    fun runUserAction(post: dev.nytweetdeck.android.model.Post, action: UserAction) = postMenuController.userAction(post, action)
    fun updateListMembership(post: dev.nytweetdeck.android.model.Post, listId: String, add: Boolean) = postMenuController.listMembership(post, listId, add)
    fun clearPostMenuFailure() = postMenuController.clearFailure()

    fun setVisibleColumns(columnIds: Set<String>) {
        val validIds = mutableState.value.columns.mapTo(HashSet()) { it.id }
        val normalized = columnIds.filterTo(LinkedHashSet()) { it in validIds }
        val newlyVisible = normalized - visibleColumnIds
        val newlyHidden = visibleColumnIds - normalized
        newlyHidden.forEach { visibilityRefreshJobs.remove(it)?.cancel() }
        visibleColumnIds = normalized
        liveDeckController.setVisibleColumns(normalized)
        newlyVisible.forEach(::scheduleVisibilityRefresh)
    }

    fun refreshVisibleColumns() {
        visibleColumnIds.forEach(::scheduleVisibilityRefresh)
    }

    fun saveColumnScrollPosition(
        columnId: String,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        firstVisibleItemKey: String? = null,
    ) {
        if (columnId !in mutableState.value.columns.mapTo(HashSet()) { it.id }) return
        val position = ColumnScrollPosition(
            firstVisibleItemKey = firstVisibleItemKey,
            firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
        )
        mutableState.update { current ->
            if (current.columnScrollPositions[columnId] == position) current else current.copy(
                columnScrollPositions = current.columnScrollPositions + (columnId to position),
            )
        }
    }

    private fun scheduleVisibilityRefresh(columnId: String) {
        visibilityRefreshJobs.remove(columnId)?.cancel()
        val current = mutableState.value
        if (!current.autoRefreshTimelines && isColumnReady(current, columnId)) return
        visibilityRefreshJobs[columnId] = viewModelScope.launch {
            delay(visibilityRefreshDelayMillis)
            val current = mutableState.value
            if (columnId in visibleColumnIds &&
                foregroundOrUntracked() &&
                (current.autoRefreshTimelines || !isColumnReady(current, columnId))
            ) {
                refreshColumn(columnId)
            }
        }
    }

    private fun foregroundOrUntracked(): Boolean = adaptiveRefreshIntervalMillis == null || foreground

    private fun isColumnReady(state: DeckUiState, columnId: String): Boolean {
        val column = state.columns.firstOrNull { it.id == columnId } ?: return false
        return when (column.kind) {
            ColumnKind.NOTIFICATIONS -> state.notifications[columnId]?.status == TimelineLoadStatus.READY
            ColumnKind.TRENDS -> state.trends[columnId]?.status == TimelineLoadStatus.READY
            ColumnKind.MESSAGES -> state.messages[columnId]?.status == TimelineLoadStatus.READY
            else -> state.timelines[columnId]?.status == TimelineLoadStatus.READY
        }
    }

    private fun findLoadedPost(postId: String): Post? {
        val snapshot = mutableState.value
        snapshot.postDetail.page?.let { page ->
            if (page.post.id == postId) return page.post
            page.contextPosts.firstOrNull { it.id == postId }?.let { return it }
            page.replies.firstOrNull { it.post.id == postId }?.post?.let { return it }
        }
        snapshot.timelines.values.forEach { timeline ->
            timeline.posts.firstOrNull { it.id == postId }?.let { return it }
        }
        snapshot.notifications.values.forEach { notifications ->
            notifications.page?.posts?.firstOrNull { it.id == postId }?.let { return it }
        }
        return snapshot.userProfile.posts.firstOrNull { it.id == postId }
    }

    override fun onCleared() {
        visibilityRefreshJobs.values.forEach(Job::cancel)
        visibilityRefreshJobs.clear()
        liveDeckController.close()
        metadataRefreshController.close()
        super.onCleared()
    }
    fun setForeground(value: Boolean) {
        foreground = value
        liveDeckController.setForeground(value)
        if (value) metadataRefreshController.refresh()
    }

    fun refreshXApiMetadata() = metadataRefreshController.refresh(force = true)

    private fun rememberSelectedAccountColumns() {
        val current = mutableState.value
        val accountId = current.selectedAccountId ?: return
        accountColumnCaches[accountId] = AccountColumnSnapshot(
            timelines = current.timelines,
            notifications = current.notifications,
            trends = current.trends,
            messages = current.messages,
        )
    }

    fun resolveUserColumn(input: String) {
        val repository = userDirectoryRepository ?: return
        val store = accountStore ?: return
        val account = mutableState.value.selectedAccountId
            ?.let { runCatching { store.requireAccount(it) }.getOrNull() }
            ?: return
        mutableState.update {
            it.copy(targetPicker = TargetPickerState(TimelineLoadStatus.LOADING, ColumnKind.USER))
        }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                repository.resolve(account, input, Locale.getDefault().toLanguageTag().ifBlank { "ja" })
            }
            withContext(Dispatchers.Main.immediate) {
                result.fold(
                    onSuccess = { user ->
                        val title = user.displayName ?: user.username?.let { "@$it" } ?: user.id
                        val id = addColumn(ColumnKind.USER, title, user.id)
                        mutableState.update {
                            it.copy(
                                targetPicker = TargetPickerState(
                                    TimelineLoadStatus.READY,
                                    ColumnKind.USER,
                                    completedColumnId = id,
                                ),
                            )
                        }
                    },
                    onFailure = {
                        mutableState.update {
                            it.copy(targetPicker = TargetPickerState(TimelineLoadStatus.FAILED, ColumnKind.USER))
                        }
                    },
                )
            }
        }
    }

    fun openListPicker() = listPickerController.open()

    fun selectListPickerScope(scope: ListPickerScope) = listPickerController.selectScope(scope)

    fun searchListCandidates(query: String) = listPickerController.search(query)

    fun addListColumn(option: ListOption) {
        addColumn(ColumnKind.LIST, option.name, option.id)
    }

    fun clearTargetPicker() {
        mutableState.update { it.copy(targetPicker = TargetPickerState()) }
    }

    private inline fun mutate(transform: (DeckUiState) -> DeckUiState) {
        val current = mutableState.value
        if (current.isInitializing) return
        val transformed = transform(current)
        if (transformed == current) return
        val updated = transformed.copy(layoutRevision = current.layoutRevision + 1)
        mutableState.value = updated
        if (settingsStore != null) saveRequests.trySend(updated)
    }
}
