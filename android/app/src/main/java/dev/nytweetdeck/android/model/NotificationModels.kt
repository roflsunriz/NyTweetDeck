package dev.nytweetdeck.android.model

/** X Web APIの通知タイムライン応答を画面で扱いやすい形に正規化したページ。 */
data class NotificationPage(
    val notifications: List<Notification>,
    val posts: List<Post>,
    val nextCursor: String?,
)

data class Notification(
    val id: String,
    val kind: String,
    val text: String,
    val noteId: String?,
    val postId: String?,
    val actors: List<NotificationActor>,
    val imageUrls: List<String>,
)

data class NotificationActor(
    val id: String?,
    val username: String?,
    val displayName: String?,
    val avatarUrl: String?,
)

/** 通知から開くコミュニティノート本文と対象ポストを特定するための情報。 */
data class CommunityNoteDetail(
    val noteId: String,
    val text: String,
    val sources: List<CommunityNoteSource>,
    val targetPostId: String,
)

data class CommunityNoteSource(
    val fromIndex: Int,
    val toIndex: Int,
    val url: String,
)
