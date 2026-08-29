package dev.nytweetdeck.android.model

enum class TimelineLoadStatus {
    IDLE,
    LOADING,
    READY,
    FAILED,
}

data class ColumnTimelineState(
    val status: TimelineLoadStatus = TimelineLoadStatus.IDLE,
    val posts: List<Post> = emptyList(),
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val newPostCount: Int = 0,
    val newPostAvatarUrls: List<String> = emptyList(),
    val refreshGeneration: Long = 0,
)

data class NotificationColumnState(
    val status: TimelineLoadStatus = TimelineLoadStatus.IDLE,
    val page: NotificationPage? = null,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val newItemCount: Int = 0,
    val newItemAvatarUrls: List<String> = emptyList(),
    val refreshGeneration: Long = 0,
)

data class TrendColumnState(
    val status: TimelineLoadStatus = TimelineLoadStatus.IDLE,
    val page: TrendPage? = null,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val newItemCount: Int = 0,
    val refreshGeneration: Long = 0,
)

data class DirectMessageColumnState(
    val status: TimelineLoadStatus = TimelineLoadStatus.IDLE,
    val page: DirectMessagePage? = null,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val newItemCount: Int = 0,
    val newItemAvatarUrls: List<String> = emptyList(),
    val refreshGeneration: Long = 0,
)

data class ColumnScrollPosition(
    val firstVisibleItemKey: String? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

enum class PostActionType {
    LIKE,
    REPOST,
    BOOKMARK,
}

enum class ComposerMode {
    POST,
    REPLY,
    QUOTE,
}

enum class ComposerStatus {
    IDLE,
    SENDING,
    SUCCEEDED,
    FAILED,
}

data class ComposerUiState(
    val mode: ComposerMode = ComposerMode.POST,
    val targetPostId: String? = null,
    val status: ComposerStatus = ComposerStatus.IDLE,
)

data class TargetPickerState(
    val status: TimelineLoadStatus = TimelineLoadStatus.IDLE,
    val kind: ColumnKind? = null,
    val listOptions: List<ListOption> = emptyList(),
    val completedColumnId: String? = null,
)

enum class ColumnKind {
    HOME_FOR_YOU,
    HOME_FOLLOWING,
    NOTIFICATIONS,
    MESSAGES,
    TRENDS,
    SEARCH,
    HISTORY,
    USER,
    LIST,
}

enum class MainMenuItemId {
    COMPOSE,
    SEARCH,
    HOME,
    NOTIFICATIONS,
    MESSAGES,
    TRENDS,
    FOLLOWING,
    CHAT,
    GROK,
    PREMIUM,
    PROFILE,
    COMMUNITIES,
    CREATOR_STUDIO,
    BUSINESS,
    ADS,
    SPACES,
}

val DefaultMainMenuItems = listOf(
    MainMenuItemId.COMPOSE,
    MainMenuItemId.SEARCH,
    MainMenuItemId.HOME,
    MainMenuItemId.NOTIFICATIONS,
    MainMenuItemId.MESSAGES,
    MainMenuItemId.TRENDS,
)

data class DeckColumn(
    val id: String,
    val kind: ColumnKind,
    val title: String,
    val target: String? = null,
)

data class CapturedWebSession(
    val profileName: String,
    val userId: String,
    val authToken: String,
    val csrfToken: String,
)

data class AccountUiModel(
    val accountId: String,
    val userId: String,
    val username: String,
    val displayName: String,
)

enum class AccountAuthStatus {
    IDLE,
    VERIFYING,
    FAILED,
}

data class DeckUiState(
    val isInitializing: Boolean = false,
    val columns: List<DeckColumn> = emptyList(),
    val selectedMenu: ColumnKind = ColumnKind.HOME_FOR_YOU,
    val useDarkTheme: Boolean = true,
    val compactDensity: Boolean = false,
    val replySort: RankingMode = RankingMode.RELEVANCE,
    val accounts: List<AccountUiModel> = emptyList(),
    val selectedAccountId: String? = null,
    val accountAuthStatus: AccountAuthStatus = AccountAuthStatus.IDLE,
    val timelines: Map<String, ColumnTimelineState> = emptyMap(),
    val notifications: Map<String, NotificationColumnState> = emptyMap(),
    val trends: Map<String, TrendColumnState> = emptyMap(),
    val messages: Map<String, DirectMessageColumnState> = emptyMap(),
    val columnScrollPositions: Map<String, ColumnScrollPosition> = emptyMap(),
    val pendingPostActions: Map<String, Set<PostActionType>> = emptyMap(),
    val failedPostActions: Map<String, Set<PostActionType>> = emptyMap(),
    val composer: ComposerUiState = ComposerUiState(),
    val postDetail: PostDetailUiState = PostDetailUiState(),
    val targetPicker: TargetPickerState = TargetPickerState(),
    val mainMenuItems: List<MainMenuItemId> = DefaultMainMenuItems,
)
