package dev.nytweetdeck.android.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
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
import dev.nytweetdeck.android.model.CapturedWebSession
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.model.ComposerMode
import dev.nytweetdeck.android.model.ComposerStatus
import dev.nytweetdeck.android.model.ThemeMode
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XApiEnvironment
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OpenDialog {
    ADD_COLUMN,
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
        )
    }
    val viewModel = providedViewModel ?: viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openDialog by remember { mutableStateOf<OpenDialog?>(null) }
    var transferStatus by remember { mutableStateOf(TransferStatus.NONE) }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
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
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://x.com/i/status/$postId")
        }
        context.startActivity(Intent.createChooser(share, sharePostLabel))
    }
    val replyToPost: (String) -> Unit = { postId ->
        viewModel.openComposer(ComposerMode.REPLY, postId)
        openDialog = OpenDialog.COMPOSER
    }
    val downloadPostMedia: (String) -> Unit = { postId ->
        val urls = state.findPost(postId)?.media.orEmpty()
            .mapNotNull { it.url ?: it.previewUrl }
            .map(String::toUri)
            .filter { uri -> uri.scheme == "https" && uri.host in DOWNLOAD_MEDIA_HOSTS }
        val result = runCatching {
            require(urls.isNotEmpty()) { "ダウンロード可能なメディアがありません。" }
            val manager = context.getSystemService(DownloadManager::class.java)
            urls.forEachIndexed { index, uri ->
                val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "bin")
                    .takeIf { it.matches(Regex("[A-Za-z0-9]{1,5}")) } ?: "bin"
                manager.enqueue(
                    DownloadManager.Request(uri)
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                        )
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "NyTweetDeck-$postId-${index + 1}.$extension",
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
    NyTweetDeckTheme(
        darkTheme = darkTheme,
        accentColor = state.accentColor,
        fontSize = state.fontSize,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .navigationBarsPadding(),
            ) {
                MainMenu(
                    menuItems = state.mainMenuItems,
                    onActivate = activateMenuItem,
                    onEditMenu = { openDialog = OpenDialog.MENU_EDITOR },
                    onAccounts = { openDialog = OpenDialog.ACCOUNTS },
                    onSettings = { openDialog = OpenDialog.SETTINGS },
                )
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
                    onNotificationClick = { notification ->
                        notification.noteId?.let(viewModel::openCommunityNote)
                            ?: notification.postId?.let(viewModel::openPostDetail)
                    },
                    translationStates = state.postTranslations,
                    autoTranslatePosts = state.autoTranslatePosts,
                    onTranslationNeeded = viewModel::requestPostTranslation,
                    onTranslationRetry = viewModel::retryPostTranslation,
                    onToggleOriginal = viewModel::togglePostOriginal,
                )
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
                onLoadLists = viewModel::loadListCandidates,
                onSelectList = viewModel::addListColumn,
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
                onExport = {
                    transferStatus = TransferStatus.NONE
                    exportLauncher.launch("NyTweetDeck-settings.json")
                },
                onImport = {
                    transferStatus = TransferStatus.NONE
                    importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                transferStatus = transferStatus,
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
                val uri = intent.data
                if (uri?.scheme == "https" && uri.host != null) context.startActivity(intent)
            },
            onPostClick = viewModel::openPostDetail,
            onQuoteClick = viewModel::openPostDetail,
            onReplyClick = replyToPost,
            onRepostClick = { viewModel.togglePostAction(it, PostActionType.REPOST) },
            onLikeClick = { viewModel.togglePostAction(it, PostActionType.LIKE) },
            onBookmarkClick = { viewModel.togglePostAction(it, PostActionType.BOOKMARK) },
            onShareClick = sharePost,
            onDownloadClick = downloadPostMedia,
            onArticleClick = viewModel::openArticle,
            translationStates = state.postTranslations,
            autoTranslatePosts = state.autoTranslatePosts,
            onTranslationNeeded = viewModel::requestPostTranslation,
            onTranslationRetry = viewModel::retryPostTranslation,
            onToggleOriginal = viewModel::togglePostOriginal,
        )
    }
}

private val DOWNLOAD_MEDIA_HOSTS = setOf("pbs.twimg.com", "video.twimg.com")

private fun dev.nytweetdeck.android.model.DeckUiState.findPost(postId: String): Post? {
    val detailPosts = postDetail.page?.let { page ->
        sequenceOf(page.post) + page.replies.asSequence().map { it.post }
    } ?: emptySequence()
    return timelines.values.asSequence().flatMap { it.posts.asSequence() }
        .plus(detailPosts)
        .firstOrNull { it.id == postId }
}
