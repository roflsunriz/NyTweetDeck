package dev.nytweetdeck.android.model

/** X Web APIのタイムライン応答を画面で扱いやすい形に正規化したページ。 */
data class TimelinePage(
    val posts: List<Post>,
    val nextCursor: String?,
)

data class Post(
    val id: String,
    val text: String,
    val language: String?,
    val createdAt: String?,
    val author: Author,
    val repostedBy: Author?,
    val conversationSection: String?,
    val replyCount: Long,
    val repostCount: Long,
    val quoteCount: Long,
    val likeCount: Long,
    val bookmarkCount: Long,
    val viewCount: Long,
    val liked: Boolean,
    val reposted: Boolean,
    val bookmarked: Boolean,
    val replyToPostId: String?,
    val replyToUsername: String?,
    val quotedPostId: String?,
    val quotedPost: EmbeddedPost?,
    val communityNote: CommunityNote?,
    val preTranslated: Translation?,
    val article: Article?,
    val media: List<Media>,
    val links: List<TextLink> = emptyList(),
)

data class Author(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val verified: Boolean,
)

data class EmbeddedPost(
    val id: String,
    val text: String,
    val language: String?,
    val createdAt: String?,
    val author: Author,
    val preTranslated: Translation?,
    val article: Article?,
    val media: List<Media>,
    val links: List<TextLink> = emptyList(),
)

data class CommunityNote(
    val title: String?,
    val text: String?,
    val footer: String?,
    val noteId: String? = null,
    val language: String? = null,
    val isTranslatable: Boolean? = null,
    val sources: List<CommunityNoteSource> = emptyList(),
)

data class Translation(
    val text: String,
    val sourceLanguage: String?,
    val targetLanguage: String,
    val provider: String,
)

data class Article(
    val id: String,
    val title: String,
    val previewText: String?,
    val body: String?,
    val coverImageUrl: String?,
    val url: String,
)

data class Media(
    val id: String,
    val type: String,
    val url: String?,
    val previewUrl: String?,
    val variants: List<VideoVariant> = emptyList(),
)

data class VideoVariant(
    val url: String,
    val bitrate: Long?,
)

data class TextLink(
    val url: String,
    val displayText: String,
)
