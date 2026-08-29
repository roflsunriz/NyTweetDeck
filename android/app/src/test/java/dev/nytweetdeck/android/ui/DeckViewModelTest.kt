package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.data.NotificationRepository
import dev.nytweetdeck.android.data.PostActionRepository
import dev.nytweetdeck.android.data.PostComposerRepository
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.data.XPostTranslationEndpoint
import dev.nytweetdeck.android.model.CapturedWebSession
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.model.ComposerMode
import dev.nytweetdeck.android.model.ComposerStatus
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.ArticleReaderStatus
import dev.nytweetdeck.android.model.TranslationCandidate
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.model.ThemeMode
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.VerifiedWebSession
import dev.nytweetdeck.android.xapi.VerifiedXAccount
import dev.nytweetdeck.android.xapi.XSessionCredentials
import dev.nytweetdeck.android.xapi.XSessionVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun addAndRemoveColumnUpdatesObservableState() {
        val viewModel = DeckViewModel()

        viewModel.addColumn(ColumnKind.HOME_FOR_YOU, "Home")
        val column = viewModel.state.value.columns.single()
        assertEquals(ColumnKind.HOME_FOR_YOU, column.kind)

        viewModel.removeColumn(column.id)
        assertTrue(viewModel.state.value.columns.isEmpty())
    }

    @Test
    fun displayPreferencesUpdateIndependently() {
        val viewModel = DeckViewModel()

        viewModel.setDarkTheme(false)
        viewModel.setCompactDensity(true)

        assertEquals(false, viewModel.state.value.useDarkTheme)
        assertEquals(true, viewModel.state.value.compactDensity)
    }

    @Test
    fun trendSearchHistoryDeduplicatesCaseInsensitivelyAndCapsAtTwenty() {
        val viewModel = DeckViewModel()

        viewModel.openTrendSearch("  Android  ")
        viewModel.recordTrendSearch("android")
        repeat(21) { index -> viewModel.recordTrendSearch("query-$index") }

        assertEquals(20, viewModel.state.value.trendSearchHistory.size)
        assertEquals("query-20", viewModel.state.value.trendSearchHistory.first())
        assertEquals(1, viewModel.state.value.columns.count { it.target == "Android" })

        viewModel.clearTrendSearchHistory()
        assertTrue(viewModel.state.value.trendSearchHistory.isEmpty())
    }

    @Test
    fun columnsCanBeReorderedWithoutChangingTheirIdentity() {
        val viewModel = DeckViewModel()
        viewModel.addColumn(ColumnKind.HOME_FOR_YOU, "Home")
        viewModel.addColumn(ColumnKind.NOTIFICATIONS, "Notifications")
        val firstId = viewModel.state.value.columns.first().id

        viewModel.moveColumn(firstId, 1)

        assertEquals(firstId, viewModel.state.value.columns.last().id)
    }

    @Test
    fun capturedCredentialsAreVerifiedAndPersistedWithoutEnteringUiState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            val verifier = XSessionVerifier { session ->
                VerifiedWebSession(
                    profileName = session.profileName,
                    account = VerifiedXAccount("42", "nytd_user", "NyTD User"),
                    credentials = XSessionCredentials(
                        "bearer-fixture",
                        session.authToken,
                        session.csrfToken,
                    ),
                )
            }
            val viewModel = DeckViewModel(
                settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath()),
                accountStoreFile = accountFile,
                sessionVerifier = verifier,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.acceptCapturedSession(
                CapturedWebSession("profile-fixture", "42", "auth-fixture", "csrf-fixture"),
            )
            advanceUntilIdle()

            assertEquals("42", viewModel.state.value.selectedAccountId)
            assertEquals("nytd_user", viewModel.state.value.accounts.single().username)
            assertFalse(viewModel.state.value.toString().contains("auth-fixture"))
            assertEquals("42", AccountStore(accountFile).selectedAccountId())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun restoredHomeColumnLoadsAuthenticatedTimeline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath())
            settingsStore.save(
                DeckUiState(
                    columns = listOf(DeckColumn("home", ColumnKind.HOME_FOR_YOU, "Home")),
                ),
            )
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets(
                    "7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7",
                ),
                select = true,
            )
            val timelineRepository = TimelineRepository(
                GraphQlExecutor { _, _, _, _ ->
                    """{
                      "data":{"tweet":{"__typename":"Tweet","rest_id":"99",
                      "legacy":{"full_text":"Loaded from X","created_at":"Sat Aug 29 00:00:00 +0000 2026"},
                      "core":{"user_results":{"result":{"__typename":"User","rest_id":"7",
                      "core":{"screen_name":"nytd","name":"NyTD"}}}}}}
                    }""".trimIndent()
                },
            )
            val viewModel = DeckViewModel(
                settingsStore = settingsStore,
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                timelineRepository = timelineRepository,
                ioDispatcher = dispatcher,
            )

            advanceUntilIdle()
            viewModel.setVisibleColumns(setOf("home"))
            advanceUntilIdle()

            val timeline = requireNotNull(viewModel.state.value.timelines["home"])
            assertEquals(TimelineLoadStatus.READY, timeline.status)
            assertEquals("Loaded from X", timeline.posts.single().text)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun loadingOlderPageAppendsPostsWithoutDuplicates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath())
            settingsStore.save(
                DeckUiState(columns = listOf(DeckColumn("home", ColumnKind.HOME_FOR_YOU, "Home"))),
            )
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7"),
                select = true,
            )
            val repository = TimelineRepository(
                GraphQlExecutor { _, _, variables, _ ->
                    if (variables["cursor"] == null) timelineJson("1", "first", "next")
                    else """{"data":{"entries":[
                      ${tweetJson("1", "first")},${tweetJson("2", "second")}
                    ]}}"""
                },
            )
            val viewModel = DeckViewModel(
                settingsStore = settingsStore,
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                timelineRepository = repository,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()
            viewModel.setVisibleColumns(setOf("home"))
            advanceUntilIdle()

            viewModel.loadMore("home")
            advanceUntilIdle()

            val timeline = requireNotNull(viewModel.state.value.timelines["home"])
            assertEquals(listOf("1", "2"), timeline.posts.map { it.id })
            assertFalse(timeline.isLoadingMore)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun surfacesAConcurrentSettingsRevisionConflictWithoutOverwritingDisk() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val path = temporaryFolder.root.resolve("layout/settings.json").toPath()
            val store = DeckSettingsStore(path)
            val initial = DeckUiState(layoutRevision = 2)
            store.save(initial)
            val viewModel = DeckViewModel(settingsStore = store, ioDispatcher = dispatcher)
            advanceUntilIdle()

            val concurrent = initial.copy(themeMode = ThemeMode.LIGHT, layoutRevision = 3)
            store.save(concurrent)
            viewModel.setCompactDensity(true)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.settingsConflict)
            assertEquals(concurrent, store.load())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun refreshPrependsOnlyNewPostsAndTracksBannerWithoutRefreshingHiddenColumn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath())
            settingsStore.save(
                DeckUiState(columns = listOf(
                    DeckColumn("home", ColumnKind.HOME_FOR_YOU, "Home"),
                    DeckColumn("following", ColumnKind.HOME_FOLLOWING, "Following"),
                )),
            )
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7"),
                select = true,
            )
            var calls = 0
            val repository = TimelineRepository(GraphQlExecutor { _, _, _, _ ->
                calls++
                if (calls == 1) timelineJson("1", "first", "next")
                else """{"data":{"entries":[${tweetJson("2", "new")},${tweetJson("1", "first")}],
                    "cursor":{"cursorType":"Bottom","value":"next"}}}"""
            })
            val viewModel = DeckViewModel(
                settingsStore = settingsStore,
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                timelineRepository = repository,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()
            viewModel.setVisibleColumns(setOf("home"))
            advanceUntilIdle()
            assertEquals(1, calls)

            viewModel.refreshColumn("home")
            advanceUntilIdle()

            val timeline = requireNotNull(viewModel.state.value.timelines["home"])
            assertEquals(listOf("2", "1"), timeline.posts.map { it.id })
            assertEquals(1, timeline.newPostCount)
            assertTrue(timeline.newPostAvatarUrls.size <= 5)
            assertEquals(false, viewModel.state.value.timelines.containsKey("following"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun visibleColumnUsesRetainedContentBeforeDelayedRefreshAndHiddenColumnCancelsIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath())
            settingsStore.save(
                DeckUiState(columns = listOf(DeckColumn("home", ColumnKind.HOME_FOR_YOU, "Home"))),
            )
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7"),
                select = true,
            )
            var calls = 0
            val repository = TimelineRepository(GraphQlExecutor { _, _, _, _ ->
                calls++
                timelineJson("1", "retained", "next")
            })
            val viewModel = DeckViewModel(
                settingsStore = settingsStore,
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                timelineRepository = repository,
                ioDispatcher = dispatcher,
                visibilityRefreshDelayMillis = 750L,
            )
            advanceUntilIdle()

            viewModel.setVisibleColumns(setOf("home"))
            advanceTimeBy(749L)
            runCurrent()
            assertEquals(0, calls)

            viewModel.setVisibleColumns(emptySet())
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(0, calls)

            viewModel.setVisibleColumns(setOf("home"))
            advanceTimeBy(750L)
            advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals("retained", viewModel.state.value.timelines["home"]?.posts?.single()?.text)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun columnScrollPositionIsStoredPerColumnAndRemovedWithColumn() {
        val viewModel = DeckViewModel()
        val first = requireNotNull(viewModel.addColumn(ColumnKind.HOME_FOR_YOU, "Home"))
        val second = requireNotNull(viewModel.addColumn(ColumnKind.NOTIFICATIONS, "Notifications"))

        viewModel.saveColumnScrollPosition(first, 17, 93)
        viewModel.saveColumnScrollPosition(second, 4, 21)

        assertEquals(17, viewModel.state.value.columnScrollPositions[first]?.firstVisibleItemIndex)
        assertEquals(93, viewModel.state.value.columnScrollPositions[first]?.firstVisibleItemScrollOffset)
        assertEquals(4, viewModel.state.value.columnScrollPositions[second]?.firstVisibleItemIndex)

        viewModel.removeColumn(first)
        assertFalse(viewModel.state.value.columnScrollPositions.containsKey(first))
        assertTrue(viewModel.state.value.columnScrollPositions.containsKey(second))
    }

    @Test
    fun notificationRefreshRetainsReadyPageAndMergesNewItems() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath())
            settingsStore.save(
                DeckUiState(columns = listOf(
                    DeckColumn("notifications", ColumnKind.NOTIFICATIONS, "Notifications"),
                )),
            )
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7"),
                select = true,
            )
            var calls = 0
            val repository = NotificationRepository(GraphQlExecutor { _, _, _, _ ->
                calls++
                if (calls == 1) notificationJson("old") else notificationJson("new", "old")
            })
            val viewModel = DeckViewModel(
                settingsStore = settingsStore,
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                notificationRepository = repository,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()
            viewModel.setVisibleColumns(setOf("notifications"))
            advanceUntilIdle()
            assertEquals(
                listOf("old"),
                viewModel.state.value.notifications["notifications"]?.page?.notifications?.map { it.id },
            )

            viewModel.refreshColumn("notifications")
            val refreshing = requireNotNull(viewModel.state.value.notifications["notifications"])
            assertEquals(TimelineLoadStatus.READY, refreshing.status)
            assertEquals(listOf("old"), refreshing.page?.notifications?.map { it.id })
            assertTrue(refreshing.isRefreshing)

            advanceUntilIdle()
            val refreshed = requireNotNull(viewModel.state.value.notifications["notifications"])
            assertEquals(listOf("new", "old"), refreshed.page?.notifications?.map { it.id })
            assertEquals(1, refreshed.newItemCount)
            assertFalse(refreshed.isRefreshing)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun accountSwitchRestoresEachAccountsRetainedColumnsBeforeDelayedRefresh() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath())
            settingsStore.save(
                DeckUiState(columns = listOf(DeckColumn("home", ColumnKind.HOME_FOR_YOU, "Home"))),
            )
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).apply {
                addOrReplace(
                    AccountSecrets("1", "1", "one", "One", "bearer", "auth-1", "csrf", "profile-1"),
                    select = true,
                )
                addOrReplace(
                    AccountSecrets("2", "2", "two", "Two", "bearer", "auth-2", "csrf", "profile-2"),
                    select = false,
                )
            }
            val repository = TimelineRepository(GraphQlExecutor { credentials, _, _, _ ->
                val accountNumber = credentials.authToken.removePrefix("auth-")
                timelineJson(accountNumber, "account-$accountNumber", "next-$accountNumber")
            })
            val viewModel = DeckViewModel(
                settingsStore = settingsStore,
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                timelineRepository = repository,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()
            viewModel.setVisibleColumns(setOf("home"))
            advanceUntilIdle()
            assertEquals("account-1", viewModel.state.value.timelines["home"]?.posts?.single()?.text)

            viewModel.selectAccount("2")
            runCurrent()
            assertEquals("2", viewModel.state.value.selectedAccountId)
            assertTrue(viewModel.state.value.timelines.isEmpty())
            advanceTimeBy(750L)
            advanceUntilIdle()
            assertEquals("account-2", viewModel.state.value.timelines["home"]?.posts?.single()?.text)

            viewModel.selectAccount("1")
            runCurrent()
            assertEquals("1", viewModel.state.value.selectedAccountId)
            assertEquals("account-1", viewModel.state.value.timelines["home"]?.posts?.single()?.text)
            viewModel.setVisibleColumns(emptySet())
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun postActionIsOptimisticThenRollsBackOnlyTheFailedAction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath())
            settingsStore.save(
                DeckUiState(columns = listOf(DeckColumn("home", ColumnKind.HOME_FOR_YOU, "Home"))),
            )
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7"),
                select = true,
            )
            val purposes = mutableListOf<String>()
            var failUnlike = false
            val executor = GraphQlExecutor { _, purpose, _, _ ->
                if (purpose == "homeForYou") {
                    timelineJson("1", "action target", "next")
                } else {
                    purposes += purpose
                    if (purpose == "unlike" && failUnlike) error("fixture failure")
                    "{}"
                }
            }
            val viewModel = DeckViewModel(
                settingsStore = settingsStore,
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                timelineRepository = TimelineRepository(executor),
                postActionRepository = PostActionRepository(executor),
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()
            viewModel.setVisibleColumns(setOf("home"))
            advanceUntilIdle()

            viewModel.togglePostAction("1", PostActionType.LIKE)
            viewModel.togglePostAction("1", PostActionType.LIKE)
            advanceUntilIdle()
            var post = requireNotNull(viewModel.state.value.timelines["home"]?.posts?.single())
            assertFalse(post.liked)
            assertEquals(0L, post.likeCount)
            assertTrue(purposes.isEmpty())
            assertTrue(viewModel.state.value.pendingPostActions.isEmpty())

            viewModel.togglePostAction("1", PostActionType.LIKE)
            post = requireNotNull(viewModel.state.value.timelines["home"]?.posts?.single())
            assertTrue(post.liked)
            assertEquals(1L, post.likeCount)
            assertTrue(PostActionType.LIKE in viewModel.state.value.pendingPostActions["1"].orEmpty())
            advanceUntilIdle()
            assertEquals(listOf("like"), purposes)
            assertTrue(viewModel.state.value.pendingPostActions.isEmpty())

            failUnlike = true
            viewModel.togglePostAction("1", PostActionType.LIKE)
            post = requireNotNull(viewModel.state.value.timelines["home"]?.posts?.single())
            assertFalse(post.liked)
            assertEquals(0L, post.likeCount)
            advanceUntilIdle()

            post = requireNotNull(viewModel.state.value.timelines["home"]?.posts?.single())
            assertTrue(post.liked)
            assertEquals(1L, post.likeCount)
            assertTrue(PostActionType.LIKE in viewModel.state.value.failedPostActions["1"].orEmpty())
            assertTrue(viewModel.state.value.pendingPostActions.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun composerSubmitsOnceAndReportsSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7"),
                select = true,
            )
            var purpose: String? = null
            var submittedText: String? = null
            val executor = GraphQlExecutor { _, requestPurpose, variables, _ ->
                purpose = requestPurpose
                submittedText = variables["tweet_text"] as? String
                timelineJson("123", "created", "next")
            }
            val viewModel = DeckViewModel(
                settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath()),
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                postComposerRepository = PostComposerRepository(executor),
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.openComposer(ComposerMode.POST)
            viewModel.submitPost("  hello Android  ")
            viewModel.submitPost("must not be submitted twice")
            assertEquals(ComposerStatus.SENDING, viewModel.state.value.composer.status)
            advanceUntilIdle()

            assertEquals("createPost", purpose)
            assertEquals("hello Android", submittedText)
            assertEquals(ComposerStatus.SUCCEEDED, viewModel.state.value.composer.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun articleReaderLoadsMissingBodyFromPostDetail() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile"),
                select = true,
            )
            val executor = GraphQlExecutor { _, purpose, _, _ ->
                if (purpose == "postDetail") articleDetailJson() else "{\"data\":{}}"
            }
            val viewModel = DeckViewModel(
                settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath()),
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                postDetailRepository = PostDetailRepository(executor),
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.openArticle(
                "700",
                Article("701", "Article", "Preview", null, null, "https://x.com/i/article/701"),
            )
            assertEquals(ArticleReaderStatus.LOADING, viewModel.state.value.articleReader.status)
            advanceUntilIdle()

            assertEquals(ArticleReaderStatus.READY, viewModel.state.value.articleReader.status)
            assertEquals("Complete body", viewModel.state.value.articleReader.article?.body)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun nestedPostDetailsRestoreThePreviousDetailBeforeClosing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile"),
                select = true,
            )
            val executor = GraphQlExecutor { _, purpose, variables, _ ->
                when (purpose) {
                    "postDetail" -> detailJson(requireNotNull(variables["tweetId"] as? String))
                    "conversation" -> "{\"data\":{\"entries\":[]}}"
                    else -> error("unexpected purpose: $purpose")
                }
            }
            val viewModel = DeckViewModel(
                settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath()),
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                postDetailRepository = PostDetailRepository(executor),
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.openPostDetail("101")
            advanceUntilIdle()
            viewModel.toggleDeemphasizedReplies()
            viewModel.openPostDetail("102")
            advanceUntilIdle()
            assertEquals("102", viewModel.state.value.postDetail.postId)

            viewModel.closePostDetail()
            assertEquals("101", viewModel.state.value.postDetail.postId)
            assertTrue(viewModel.state.value.postDetail.showDeemphasizedReplies)
            viewModel.closePostDetail()
            assertEquals(PostDetailStatus.CLOSED, viewModel.state.value.postDetail.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun visiblePostTranslationUpdatesUiAndHealthThroughXOnlyRepository() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val root = temporaryFolder.root
            val accountFile = root.resolve("no-backup/accounts/accounts.json")
            AccountStore(accountFile).addOrReplace(
                AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile"),
                select = true,
            )
            var source: String? = null
            val repository = PostTranslationRepository(
                XPostTranslationEndpoint { _, postId, translationSource, target ->
                    source = translationSource
                    AuthenticatedRestClient.RestResult(
                        """{"id_str":"$postId","translation":"translated-$target"}""",
                        null,
                        null,
                    )
                },
            )
            val viewModel = DeckViewModel(
                settingsStore = DeckSettingsStore(root.resolve("layout/settings.json").toPath()),
                accountStoreFile = accountFile,
                sessionVerifier = XSessionVerifier { error("not used") },
                postTranslationRepository = repository,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.requestPostTranslation(TranslationCandidate("123", "en", null))
            assertEquals(TranslationLoadStatus.LOADING, viewModel.state.value.postTranslations["123"]?.status)
            advanceUntilIdle()

            assertEquals("X", source)
            assertEquals(TranslationLoadStatus.READY, viewModel.state.value.postTranslations["123"]?.status)
            assertTrue(viewModel.state.value.translationHealth?.upstreamSuccesses == 1L)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun timelineJson(id: String, text: String, cursor: String): String =
        """{"data":{"entries":[${tweetJson(id, text)},
          {"entryId":"cursor-bottom","content":{"cursorType":"Bottom","value":"$cursor"}}
        ]}}"""

    private fun tweetJson(id: String, text: String): String =
        """{"content":{"itemContent":{"tweet_results":{"result":{
          "__typename":"Tweet","rest_id":"$id",
          "legacy":{"full_text":"$text","created_at":"Sat Aug 29 00:00:00 +0000 2026"},
          "core":{"user_results":{"result":{"__typename":"User","rest_id":"7",
          "core":{"screen_name":"nytd","name":"NyTD"}}}}
        }}}}}"""

    private fun notificationJson(vararg ids: String): String =
        ids.joinToString(
            prefix = "{\"entries\":[",
            postfix = "]}",
        ) { id ->
            """{"content":{"notification":{"id":"$id","notification_icon":"person",
              "message":{"text":"$id notification"}}}}"""
        }

    private fun articleDetailJson(): String = """
        {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"700",
        "legacy":{"full_text":"https://t.co/article","entities":{"urls":[{
        "url":"https://t.co/article","expanded_url":"https://x.com/i/article/701"}]}},
        "article":{"article_results":{"result":{"rest_id":"701","title":"Article",
        "preview_text":"Preview","plain_text":"Complete body"}}}}}}}
    """.trimIndent()

    private fun detailJson(postId: String): String =
        """{"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"$postId",
          "legacy":{"full_text":"detail $postId"}}}}}}"""
}
