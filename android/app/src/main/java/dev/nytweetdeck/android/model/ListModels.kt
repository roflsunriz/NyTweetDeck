package dev.nytweetdeck.android.model

/** X Web APIから取得した、カラム作成用リスト候補のページ。 */
data class ListDirectoryPage(
    val lists: List<ListOption>,
    val nextCursor: String?,
)

/** X Web APIのリスト候補。 */
data class ListOption(
    val id: String,
    val name: String,
    val description: String?,
    val ownerName: String?,
    val ownerUsername: String?,
    val memberCount: Long,
    val subscriberCount: Long,
    val source: String,
)
