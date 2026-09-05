package dev.nytweetdeck.android.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nytweetdeck.android.BuildConfig
import dev.nytweetdeck.android.update.ApkUpdateController
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.auth.LoginActivity
import dev.nytweetdeck.android.data.DirectMessageRepository
import dev.nytweetdeck.android.data.LayoutTransfer
import dev.nytweetdeck.android.data.ListDirectoryRepository
import dev.nytweetdeck.android.data.NotificationRepository
import dev.nytweetdeck.android.data.TimelineCache
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.data.TrendRepository
import dev.nytweetdeck.android.data.UserDirectoryRepository
import dev.nytweetdeck.android.data.PostActionRepository
import dev.nytweetdeck.android.data.PostComposerRepository
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.data.CommunityNoteRepository
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.data.UserActionRepository
import dev.nytweetdeck.android.data.ListMembershipRepository
import dev.nytweetdeck.android.model.CapturedWebSession
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.NavigationPosition
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.model.ComposerMode
import dev.nytweetdeck.android.model.ComposerStatus
import dev.nytweetdeck.android.model.ThemeMode
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.Notification as DeckNotification
import dev.nytweetdeck.android.model.PostMenuAction
import dev.nytweetdeck.android.data.UserAction
import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.update.GitHubReleaseClient
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XApiEnvironment
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OpenDialog {
    ADD_COLUMN,
    LIST_PICKER,
    ACCOUNTS,
    SETTINGS,
    MENU_EDITOR,
    COMPOSER,
}

@Composable
fun NyTweetDeckApp(providedViewModel: DeckViewModel? = null) {
    val context = LocalContext.current
    val factory = remember(context) {
        val environment = XApiEnvironment(context)
        val graphQlClient = environment.graphQlClient()
        DeckViewModelFactory(
            context.filesDir.toPath().resolve("layout").resolve("settings.json"),
            context.noBackupFilesDir.resolve("accounts").resolve("accounts.json"),
            environment,
            TimelineRepository(
                graphQlClient,
                TimelineResponseParser(),
                TimelineCache(context.cacheDir.resolve("timelines")),
            ),
            NotificationRepository(graphQlClient),
            TrendRepository(graphQlClient),
            DirectMessageRepository(environment.restClient()),
            ListDirectoryRepository(graphQlClient),
            UserDirectoryRepository(graphQlClient),
            PostActionRepository(graphQlClient),
            PostComposerRepository(graphQlClient),
            PostDetailRepository(graphQlClient),
            CommunityNoteRepository(graphQlClient),
            PostTranslationRepository(environment.restClient()),
            UserActionRepository(environment.restClient()),
            ListMembershipRepository(graphQlClient),
            environment.livePipeline(),
            environment,
        )
    }
    val viewModel = providedViewModel ?: viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openDialog by remember { mutableStateOf<OpenDialog?>(null) }
    var transferStatus by remember { mutableStateOf(TransferStatus.NONE) }
    var followNotification by remember { mutableStateOf<DeckNotification?>(null) }
    var postMenuPost by remember { mutableStateOf<Post?>(null) }
    var temporaryMainNavigationVisible by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val apkUpdateController = remember(context) {
        val client = GitHubReleaseClient()
        ApkUpdateController(
            currentVersion = BuildConfig.VERSION_NAME,
            latestApk = { withContext(Dispatchers.IO) { client.latestStableAndroidApk() } },
            download = { apk ->
                withContext(Dispatchers.IO) {
                    val manager = requireNotNull(context.getSystemService(DownloadManager::class.java))
                    val id = manager.enqueue(
                        DownloadManager.Request(apk.downloadUrl.toUri())
                            .setTitle(apk.assetName)
                            .setDescription(apk.tagName)
                            .setMimeType("application/vnd.android.package-archive")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, apk.assetName),
                    )
                    // Keep the action disabled after success, but allow retries after an OS download failure.
                    while (true) {
                        val downloadStatus = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                            check(cursor.moveToFirst()) { "APK download was removed" }
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        }
                        check(downloadStatus != DownloadManager.STATUS_FAILED) { "APK download failed" }
                        if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) break
                        delay(1_000)
                    }
                }
            },
        )
    }
    val apkUpdateStatus by apkUpdateController.status.collectAsStateWithLifecycle()
    LaunchedEffect(openDialog) {
        if (openDialog == OpenDialog.SETTINGS) apkUpdateController.check()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.isInitializing, state.appLanguageTag) {
        if (!state.isInitializing && AppLocaleController.currentLanguageTag(context) != state.appLanguageTag) {
            (context as? Activity)?.let { AppLocaleController.apply(it, state.appLanguageTag) }
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_RESUME -> viewModel.refreshVisibleColumns()
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val profileName = data.getStringExtra(LoginActivity.EXTRA_PROFILE_NAME)
        val userId = data.getStringExtra(LoginActivity.EXTRA_USER_ID)
        val authToken = data.getStringExtra(LoginActivity.EXTRA_AUTH_TOKEN)
        val csrfToken = data.getStringExtra(LoginActivity.EXTRA_CSRF_TOKEN)
        if (
            !profileName.isNullOrBlank() &&
            !userId.isNullOrBlank() &&
            !authToken.isNullOrBlank() &&
            !csrfToken.isNullOrBlank()
        ) {
            viewModel.acceptCapturedSession(
                CapturedWebSession(profileName, userId, authToken, csrfToken),
            )
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            transferStatus = runCatching {
                val document = LayoutTransfer.exportSettings(state, Instant.now())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(document.toByteArray(Charsets.UTF_8))
                    } ?: error("出力先を開けません。")
                }
            }.fold(
                onSuccess = { TransferStatus.EXPORT_SUCCESS },
                onFailure = { TransferStatus.FAILED },
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            transferStatus = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= LayoutTransfer.MAX_FILE_SIZE_BYTES) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray().also {
                            require(it.size <= LayoutTransfer.MAX_FILE_SIZE_BYTES) {
                                "設定JSONが大きすぎます。"
                            }
                        }
                    } ?: error("入力元を開けません。")
                }
                viewModel.importLayout(bytes)
            }.fold(
                onSuccess = { TransferStatus.IMPORT_SUCCESS },
                onFailure = { TransferStatus.FAILED },
            )
        }
    }
    val forYouLabel = stringResource(R.string.for_you)
    val notificationsLabel = stringResource(R.string.notifications)
    val messagesLabel = stringResource(R.string.direct_messages)
    val trendsLabel = stringResource(R.string.trends)
    val followingLabel = stringResource(R.string.following)
    val profileLabel = stringResource(R.string.profile)
    val sharePostLabel = stringResource(R.string.post_share)
    val activateMenuItem: (MainMenuItemId) -> Unit = { item ->
        when (item) {
            MainMenuItemId.COMPOSE -> {
                if (state.selectedAccountId == null) {
                    openDialog = OpenDialog.ACCOUNTS
                } else {
                    viewModel.openComposer(ComposerMode.POST)
                    openDialog = OpenDialog.COMPOSER
                }
            }
            MainMenuItemId.SEARCH -> openDialog = OpenDialog.ADD_COLUMN
            MainMenuItemId.HOME -> viewModel.addColumn(
                ColumnKind.HOME_FOR_YOU,
                forYouLabel,
            )
            MainMenuItemId.NOTIFICATIONS -> viewModel.addColumn(
                ColumnKind.NOTIFICATIONS,
                notificationsLabel,
            )
            MainMenuItemId.MESSAGES -> viewModel.addColumn(
                ColumnKind.MESSAGES,
                messagesLabel,
            )
            MainMenuItemId.TRENDS -> viewModel.addColumn(
                ColumnKind.TRENDS,
                trendsLabel,
            )
            MainMenuItemId.FOLLOWING -> viewModel.addColumn(
                ColumnKind.HOME_FOLLOWING,
                followingLabel,
            )
            MainMenuItemId.PROFILE -> {
                val accountId = state.selectedAccountId
                if (accountId == null) {
                    openDialog = OpenDialog.ACCOUNTS
                } else {
                    viewModel.addColumn(ColumnKind.USER, profileLabel, accountId)
                }
            }
            else -> externalMenuUrls[item]?.let { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
        }
    }
    val sharePost: (String) -> Unit = { postId ->
        when (sharePostOrCopy(context, postId, sharePostLabel)) {
            PostShareOutcome.COPIED -> Toast.makeText(
                context, R.string.post_link_copied, Toast.LENGTH_SHORT,
            ).show()
            PostShareOutcome.INVALID -> Toast.makeText(
                context, R.string.post_action_failed, Toast.LENGTH_SHORT,
            ).show()
            PostShareOutcome.SHARED -> Unit
        }
    }
    val replyToPost: (String) -> Unit = { postId ->
        viewModel.openComposer(ComposerMode.REPLY, postId)
        openDialog = OpenDialog.COMPOSER
    }
    val quotePost: (String) -> Unit = { postId ->
        viewModel.openComposer(ComposerMode.QUOTE, postId)
        openDialog = OpenDialog.COMPOSER
    }
    val openAuthorProfile: (Author) -> Unit = { author ->
        if (author.id.matches(Regex("[0-9]{1,24}"))) {
            viewModel.userProfileController?.open(author.id)
        }
    }
    val downloadPostMedia: (String) -> Unit = { postId ->
        val downloads = planMediaDownloads(postId, state.findPost(postId)?.media.orEmpty())
        val result = runCatching {
            require(downloads.isNotEmpty()) { "ダウンロード可能なメディアがありません。" }
            val manager = context.getSystemService(DownloadManager::class.java)
            downloads.forEach { download ->
                manager.enqueue(
                    DownloadManager.Request(download.url.toUri())
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                        )
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            download.destinationFileName,
                        ),
                )
            }
        }
        Toast.makeText(
            context,
            if (result.isSuccess) R.string.media_download_started else R.string.media_download_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    val darkTheme = when (state.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val mainNavigationVisible = state.showMainNavigation || temporaryMainNavigationVisible
    LaunchedEffect(state.showMainNavigation, temporaryMainNavigationVisible) {
        if (state.showMainNavigation) {
            temporaryMainNavigationVisible = false
        } else if (temporaryMainNavigationVisible) {
            delay(3_000)
            temporaryMainNavigationVisible = false
        }
    }
    NyTweetDeckTheme(
        darkTheme = darkTheme,
        accentColor = state.accentColor,
        fontSize = state.fontSize,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .navigationBarsPadding(),
            ) {
                val deckModifier = if (!mainNavigationVisible) {
                    Modifier.fillMaxSize()
                } else {
                    when (state.navigationPosition) {
                        NavigationPosition.LEFT -> Modifier.fillMaxSize().padding(start = 60.dp)
                        NavigationPosition.BOTTOM -> Modifier.fillMaxSize().padding(bottom = 60.dp)
                    }
                }
                Box(modifier = deckModifier) {
                    DeckContent(
                        state = state,
                        columnScrollPositions = state.columnScrollPositions,
                        onRemoveColumn = viewModel::removeColumn,
                        onAddColumn = { openDialog = OpenDialog.ADD_COLUMN },
                        onOpenAccounts = { openDialog = OpenDialog.ACCOUNTS },
                        onRefreshColumn = viewModel::refreshColumn,
                        onLoadMoreColumn = viewModel::loadMore,
                        onClearNewPostsColumn = viewModel::clearNewPosts,
                        onVisibleColumnsChanged = viewModel::setVisibleColumns,
                        onMoveColumn = viewModel::moveColumn,
                        onSaveColumnScrollPosition = viewModel::saveColumnScrollPosition,
                        onRepostClick = { postId ->
                            viewModel.togglePostAction(postId, PostActionType.REPOST)
                        },
                        onReplyClick = replyToPost,
                        onPostClick = viewModel::openPostDetail,
                        onQuoteClick = viewModel::openPostDetail,
                        onCreateQuoteClick = quotePost,
                        onAuthorClick = openAuthorProfile,
                        onLikeClick = { postId ->
                            viewModel.togglePostAction(postId, PostActionType.LIKE)
                        },
                        onBookmarkClick = { postId ->
                            viewModel.togglePostAction(postId, PostActionType.BOOKMARK)
                        },
                        onShareClick = sharePost,
                        onDownloadClick = downloadPostMedia,
                        videoAutoplay = state.videoAutoplay,
                        videoLoop = state.videoLoop,
                        videoVolume = state.videoVolume,
                        onTrendSelected = viewModel::openTrendSearch,
                        onTrendQueryCommitted = viewModel::recordTrendSearch,
                        onClearTrendHistory = viewModel::clearTrendSearchHistory,
                        onArticleClick = viewModel::openArticle,
                        onPostMenuClick = { postMenuPost = it },
                        onNotificationClick = { notification ->
                            when {
                                notification.kind == "follow" && notification.actors.isNotEmpty() -> {
                                    followNotification = notification
                                }
                                notification.noteId != null -> viewModel.openCommunityNote(notification.noteId)
                                notification.postId != null -> viewModel.openPostDetail(notification.postId)
                            }
                        },
                        translationStates = state.postTranslations,
                        autoTranslatePosts = state.autoTranslatePosts,
                        onTranslationNeeded = viewModel::requestPostTranslation,
                        onTranslationRetry = viewModel::retryPostTranslation,
                        onToggleOriginal = viewModel::togglePostOriginal,
                    )
                    if (!mainNavigationVisible) {
                        MainMenuRevealEdge(
                            position = state.navigationPosition,
                            onReveal = { temporaryMainNavigationVisible = true },
                            modifier = Modifier.align(
                                when (state.navigationPosition) {
                                    NavigationPosition.LEFT -> Alignment.CenterStart
                                    NavigationPosition.BOTTOM -> Alignment.BottomCenter
                                },
                            ),
                        )
                        FloatingActionButton(
                            onClick = { temporaryMainNavigationVisible = true },
                            modifier = Modifier
                                .align(
                                    when (state.navigationPosition) {
                                        NavigationPosition.LEFT -> Alignment.TopStart
                                        NavigationPosition.BOTTOM -> Alignment.BottomStart
                                    },
                                )
                                .padding(12.dp)
                                .testTag("show-main-navigation"),
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.reveal_main_navigation),
                            )
                        }
                    }
                }
                if (mainNavigationVisible) {
                    MainMenu(
                        position = state.navigationPosition,
                        menuItems = state.mainMenuItems,
                        onActivate = activateMenuItem,
                        onEditMenu = { openDialog = OpenDialog.MENU_EDITOR },
                        onAccounts = { openDialog = OpenDialog.ACCOUNTS },
                        onSettings = { openDialog = OpenDialog.SETTINGS },
                        modifier = Modifier.align(
                            when (state.navigationPosition) {
                                NavigationPosition.LEFT -> Alignment.CenterStart
                                NavigationPosition.BOTTOM -> Alignment.BottomCenter
                            },
                        ),
                    )
                }
            }
        }

        when (openDialog) {
            OpenDialog.ADD_COLUMN -> AddColumnDialog(
                pickerState = state.targetPicker,
                onDismiss = {
                    viewModel.clearTargetPicker()
                    openDialog = null
                },
                onAdd = { kind, title, target ->
                    viewModel.addColumn(kind, title, target)
                    openDialog = null
                },
                onResolveUser = viewModel::resolveUserColumn,
                onOpenLists = {
                    viewModel.openListPicker()
                    openDialog = OpenDialog.LIST_PICKER
                },
            )
            OpenDialog.LIST_PICKER -> ListPickerDialog(
                state = state.listPicker,
                onScopeChange = viewModel::selectListPickerScope,
                onSearch = viewModel::searchListCandidates,
                onSelect = { option ->
                    viewModel.addListColumn(option)
                    openDialog = null
                },
                onDismiss = { openDialog = null },
            )
            OpenDialog.ACCOUNTS -> AccountsDialog(
                state = state,
                onDismiss = { openDialog = null },
                onLogin = {
                    loginLauncher.launch(Intent(context, LoginActivity::class.java).apply {
                        putExtra(LoginActivity.EXTRA_PROFILE_NAME, "nytweetdeck-${UUID.randomUUID()}")
                    })
                },
                onSelectAccount = viewModel::selectAccount,
            )
            OpenDialog.SETTINGS -> SettingsDialog(
                state = state,
                onDisplaySettingsChange = viewModel::setDisplaySettings,
                selectedLanguageTag = state.appLanguageTag,
                onLanguageChange = { languageTag ->
                    viewModel.setAppLanguage(languageTag)
                    (context as? Activity)?.let { AppLocaleController.apply(it, languageTag) }
                },
                selectedTranslationLanguageTag = state.translationLanguageTag,
                onTranslationLanguageChange = viewModel::setTranslationLanguage,
                onExport = {
                    transferStatus = TransferStatus.NONE
                    exportLauncher.launch("NyTweetDeck-settings.json")
                },
                onImport = {
                    transferStatus = TransferStatus.NONE
                    importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                transferStatus = transferStatus,
                onRefreshXApiMetadata = viewModel::refreshXApiMetadata,
                apkUpdateStatus = apkUpdateStatus,
                onDownloadLatestApk = {
                    coroutineScope.launch { apkUpdateController.downloadLatest() }
                },
                onDismiss = { openDialog = null },
            )
            OpenDialog.MENU_EDITOR -> MenuEditorDialog(
                selected = state.mainMenuItems,
                onToggle = viewModel::toggleMainMenuItem,
                onMove = viewModel::moveMainMenuItem,
                onDismiss = { openDialog = null },
            )
            OpenDialog.COMPOSER -> SimpleComposerDialog(
                state = state.composer,
                onSubmit = viewModel::submitPost,
                onDismiss = {
                    viewModel.closeComposer()
                    openDialog = null
                },
            )
            null -> Unit
        }
        LaunchedEffect(state.composer.status) {
            if (state.composer.status == ComposerStatus.SUCCEEDED) {
                openDialog = null
                viewModel.closeComposer()
                viewModel.refreshVisibleColumns()
            }
        }
        UserProfileRoute(
            state = state.userProfile,
            onDismiss = { viewModel.userProfileController?.close() },
            onRetry = { viewModel.userProfileController?.retry() },
            onTabSelected = { viewModel.userProfileController?.selectTab(it) },
            onLoadMore = { viewModel.userProfileController?.loadMore() },
            onPostClick = viewModel::openPostDetail,
            onQuoteClick = viewModel::openPostDetail,
            onCreateQuoteClick = quotePost,
            onAuthorClick = openAuthorProfile,
            onReplyClick = replyToPost,
            onRepostClick = { viewModel.togglePostAction(it, PostActionType.REPOST) },
            onLikeClick = { viewModel.togglePostAction(it, PostActionType.LIKE) },
            onBookmarkClick = { viewModel.togglePostAction(it, PostActionType.BOOKMARK) },
            onShareClick = sharePost,
            onDownloadClick = downloadPostMedia,
            onArticleClick = viewModel::openArticle,
            onPostMenuClick = { postMenuPost = it },
            translationStates = state.postTranslations,
            autoTranslatePosts = state.autoTranslatePosts,
            onTranslationNeeded = viewModel::requestPostTranslation,
            onTranslationRetry = viewModel::retryPostTranslation,
            onToggleOriginal = viewModel::togglePostOriginal,
            mediaPreview = state.mediaPreview,
            videoAutoplay = state.videoAutoplay,
            videoLoop = state.videoLoop,
            videoVolume = state.videoVolume,
        )
        PostDetailDialog(
            state = state.postDetail,
            replySort = state.replySort,
            onDismiss = viewModel::closePostDetail,
            onRetry = viewModel::retryPostDetail,
            onLoadMore = viewModel::loadMorePostDetail,
            onReplySortChange = viewModel::setReplySort,
            onToggleDeemphasized = viewModel::toggleDeemphasizedReplies,
            onPostClick = viewModel::openPostDetail,
            onQuoteClick = viewModel::openPostDetail,
            onCreateQuoteClick = quotePost,
            onAuthorClick = { author ->
                viewModel.closePostDetail()
                openAuthorProfile(author)
            },
            onReplyClick = replyToPost,
            onRepostClick = { postId ->
                viewModel.togglePostAction(postId, PostActionType.REPOST)
            },
            onLikeClick = { postId ->
                viewModel.togglePostAction(postId, PostActionType.LIKE)
            },
            onImpressionClick = {},
            onBookmarkClick = { postId ->
                viewModel.togglePostAction(postId, PostActionType.BOOKMARK)
            },
            onShareClick = sharePost,
            onDownloadClick = downloadPostMedia,
            mediaPreview = state.mediaPreview,
            onArticleClick = viewModel::openArticle,
            onPostMenuClick = { postMenuPost = it },
            translationStates = state.postTranslations,
            autoTranslatePosts = state.autoTranslatePosts,
            onTranslationNeeded = viewModel::requestPostTranslation,
            onTranslationRetry = viewModel::retryPostTranslation,
            onToggleOriginal = viewModel::togglePostOriginal,
            videoAutoplay = state.videoAutoplay,
            videoLoop = state.videoLoop,
            videoVolume = state.videoVolume,
        )
        ArticleReaderDialog(
            state = state.articleReader,
            onDismiss = viewModel::closeArticle,
            onRetry = viewModel::retryArticle,
            onOpenX = { intent ->
                val uri = intent.data
                if (uri?.scheme == "https" && uri.host == "x.com") {
                    context.startActivity(intent)
                }
            },
        )
        CommunityNoteDialog(
            state = state.communityNote,
            onDismiss = viewModel::closeCommunityNote,
            onRetry = viewModel::retryCommunityNote,
            onOpenSource = { intent ->
                val verified = intent.data?.toString()?.let(::verifiedExternalHttpsUrl)
                if (verified != null) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, verified.toUri())
                            .addCategory(Intent.CATEGORY_BROWSABLE),
                    )
                }
            },
            onPostClick = { postId ->
                viewModel.closeCommunityNote()
                viewModel.openPostDetail(postId)
            },
            onQuoteClick = { postId ->
                viewModel.closeCommunityNote()
                viewModel.openPostDetail(postId)
            },
            onCreateQuoteClick = quotePost,
            onAuthorClick = { author ->
                viewModel.closeCommunityNote()
                openAuthorProfile(author)
            },
            onReplyClick = replyToPost,
            onRepostClick = { viewModel.togglePostAction(it, PostActionType.REPOST) },
            onLikeClick = { viewModel.togglePostAction(it, PostActionType.LIKE) },
            onBookmarkClick = { viewModel.togglePostAction(it, PostActionType.BOOKMARK) },
            onShareClick = sharePost,
            onDownloadClick = downloadPostMedia,
            onArticleClick = viewModel::openArticle,
            onPostMenuClick = { postMenuPost = it },
            translationStates = state.postTranslations,
            autoTranslatePosts = state.autoTranslatePosts,
            onTranslationNeeded = viewModel::requestPostTranslation,
            onTranslationRetry = viewModel::retryPostTranslation,
            onToggleOriginal = viewModel::togglePostOriginal,
        )
        followNotification?.let { notification ->
            FollowNotificationUsersDialog(
                actors = notification.actors,
                onActorClick = { actor ->
                    val actorId = actor.id
                    if (actorId?.matches(Regex("[0-9]{1,24}")) == true) {
                        val title = actor.displayName ?: actor.username?.let { "@$it" } ?: actorId
                        viewModel.addColumn(ColumnKind.USER, title, actorId)
                    } else {
                        actor.username?.let(viewModel::resolveUserColumn)
                    }
                    followNotification = null
                },
                onDismiss = { followNotification = null },
            )
        }
        postMenuPost?.let { post ->
            PostMenuDialog(
                post = post,
                onAction = { selected, action ->
                    when (action) {
                        PostMenuAction.NOT_INTERESTED -> viewModel.hidePost(selected.id)
                        PostMenuAction.FOLLOW -> viewModel.runUserAction(selected, UserAction.FOLLOW)
                        PostMenuAction.MUTE -> viewModel.runUserAction(selected, UserAction.MUTE)
                        PostMenuAction.BLOCK -> viewModel.runUserAction(selected, UserAction.BLOCK)
                        PostMenuAction.ACTIVITY -> context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://x.com/i/status/${selected.id}/analytics".toUri()),
                        )
                        PostMenuAction.EMBED -> context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://publish.twitter.com/?query=https://x.com/i/status/${selected.id}".toUri()),
                        )
                        PostMenuAction.REPORT -> context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://x.com/i/flow/report_tweet?tweet_id=${selected.id}".toUri()),
                        )
                        PostMenuAction.COMMUNITY_NOTE_REQUEST -> context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://x.com/i/communitynotes/contribute".toUri()),
                        )
                    }
                    postMenuPost = null
                },
                onListMembership = { selected, listId, add ->
                    viewModel.updateListMembership(selected, listId, add)
                    postMenuPost = null
                },
                onDismiss = { postMenuPost = null },
            )
        }
        LaunchedEffect(state.postMenuActionFailed) {
            if (state.postMenuActionFailed) {
                Toast.makeText(context, R.string.post_menu_action_failed, Toast.LENGTH_SHORT).show()
                viewModel.clearPostMenuFailure()
            }
        }
    }
}


private fun dev.nytweetdeck.android.model.DeckUiState.findPost(postId: String): Post? {
    val detailPosts = postDetail.page?.let { page ->
        sequenceOf(page.post) + page.replies.asSequence().map { it.post } + page.relatedPosts.asSequence()
    } ?: emptySequence()
    return timelines.values.asSequence().flatMap { it.posts.asSequence() }
        .plus(detailPosts)
        .firstOrNull { it.id == postId }
}
