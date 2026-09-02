package dev.nytweetdeck.android.model

import java.util.Locale

/** X Webが会話取得時に受け付ける返信ランキング値。 */
enum class RankingMode(
    val wireValue: String,
) {
    RELEVANCE("Relevance"),
    RECENCY("Recency"),
    LIKES("Likes"),
    ;

    companion object {
        fun fromReplySort(replySort: String?): RankingMode {
            val normalized = replySort?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when (if (normalized.isEmpty()) "relevance" else normalized) {
                "relevance" -> RELEVANCE
                "recency" -> RECENCY
                "likes" -> LIKES
                else -> throw IllegalArgumentException("返信の並び順が不正です。")
            }
        }
    }
}

/** 会話応答のnative `conversationSection` を、UIが表示方針を決めやすい形へ正規化した分類。 */
enum class ReplyQuality {
    HIGH_QUALITY,
    LOW_QUALITY,
    ABUSIVE_QUALITY,
    UNSPECIFIED,
    ;

    val isDeemphasized: Boolean
        get() = this == LOW_QUALITY || this == ABUSIVE_QUALITY

    companion object {
        fun fromConversationSection(section: String?): ReplyQuality = when (section) {
            "HighQuality" -> HIGH_QUALITY
            "LowQuality" -> LOW_QUALITY
            "AbusiveQuality" -> ABUSIVE_QUALITY
            else -> UNSPECIFIED
        }
    }
}

data class ConversationReply(
    val post: Post,
    val quality: ReplyQuality = ReplyQuality.fromConversationSection(post.conversationSection),
)

/** 対象ポストと、X Webの応答順を保った返信ページ。 */
data class PostDetailPage(
    val post: Post,
    val replies: List<ConversationReply>,
    val nextCursor: String?,
    val rankingMode: RankingMode,
    val contextPosts: List<Post> = emptyList(),
) {
    init {
        require(replies.none { reply -> reply.post.id == post.id }) {
            "対象ポストを返信一覧へ含めることはできません。"
        }
    }
}

enum class PostDetailStatus {
    CLOSED,
    LOADING,
    READY,
    FAILED,
}

data class PostDetailUiState(
    val status: PostDetailStatus = PostDetailStatus.CLOSED,
    val postId: String? = null,
    val page: PostDetailPage? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val showDeemphasizedReplies: Boolean = false,
)

enum class ArticleReaderStatus {
    CLOSED,
    LOADING,
    READY,
    FAILED,
}

data class ArticleReaderUiState(
    val status: ArticleReaderStatus = ArticleReaderStatus.CLOSED,
    val postId: String? = null,
    val article: Article? = null,
)
