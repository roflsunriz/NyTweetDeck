package dev.nytweetdeck.android.model

/** X WebのDM受信箱を画面で扱いやすい形に正規化したページ。 */
data class DirectMessagePage(
    val messages: List<DirectMessage>,
    val nextCursor: String?,
)

data class DirectMessage(
    val id: String,
    val conversationId: String?,
    val senderId: String,
    val senderName: String?,
    val senderUsername: String?,
    val senderAvatarUrl: String?,
    val text: String,
    val timestamp: Long,
)
