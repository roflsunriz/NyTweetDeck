package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.ConversationReply
import dev.nytweetdeck.android.model.PostDetailPage
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XApiException
import dev.nytweetdeck.android.xapi.XSessionCredentials
import java.util.LinkedHashMap

/** Loads the focal post and its conversation page through the shared authenticated GraphQL path. */
class PostDetailRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val responseParser: TimelineResponseParser = TimelineResponseParser(),
) {
    fun load(
        account: AccountSecrets,
        postId: String,
        cursor: String? = null,
        language: String = "ja",
        replySort: String? = "relevance",
        knownFocalPost: Post? = null,
    ): PostDetailPage {
        validatePostId(postId)
        val rankingMode = RankingMode.fromReplySort(replySort)
        val credentials = XSessionCredentials(
            bearerToken = account.webBearerToken,
            authToken = account.authToken,
            csrfToken = account.csrfToken,
        )
        val detailPage = if (knownFocalPost == null) {
            responseParser.parse(
                graphQlExecutor.execute(
                    credentials = credentials,
                    purpose = "postDetail",
                    variables = detailVariables(postId),
                    language = language,
                ),
            )
        } else {
            null
        }
        val conversationPage = responseParser.parseConversation(
            graphQlExecutor.execute(
                credentials = credentials,
                purpose = "conversation",
                variables = conversationVariables(postId, cursor, rankingMode),
                language = language,
            ),
        )
        val focalPost = knownFocalPost?.takeIf { post -> post.id == postId }
            ?: detailPage?.posts?.firstOrNull { post -> post.id == postId }
            ?: conversationPage.posts.firstOrNull { post -> post.id == postId }
            ?: throw XApiException("ポスト詳細応答に対象ポストがありません。", 502)
        val contextPosts = if (cursor.isNullOrBlank()) {
            loadConversationContext(account, focalPost, language, rankingMode, credentials)
        } else {
            emptyList()
        }
        val contextIds = contextPosts.mapTo(HashSet()) { it.id }
        val replies = conversationPage.posts.asSequence()
            .filter { post -> post.id != postId && post.id !in contextIds }
            .map(::ConversationReply)
            .toList()
        return PostDetailPage(
            post = focalPost,
            replies = replies,
            nextCursor = conversationPage.nextCursor,
            rankingMode = rankingMode,
            contextPosts = contextPosts,
            relatedPosts = conversationPage.relatedPosts.filter { it.id != postId && it.id !in contextIds },
        )
    }

    private fun loadConversationContext(
        account: AccountSecrets,
        focalPost: Post,
        language: String,
        rankingMode: RankingMode,
        credentials: XSessionCredentials,
    ): List<Post> {
        var parentId = focalPost.replyToPostId ?: return emptyList()
        require(POST_ID.matches(parentId)) { "返信元ポストIDの形式が不正です。" }
        val page = responseParser.parseConversation(
            graphQlExecutor.execute(
                credentials = credentials,
                purpose = "conversation",
                variables = conversationVariables(parentId, null, rankingMode),
                language = language,
            ),
        )
        val postsById = LinkedHashMap<String, Post>()
        page.posts.forEach { post -> postsById.putIfAbsent(post.id, post) }
        val context = ArrayList<Post>()
        val visited = HashSet<String>()
        while (visited.add(parentId)) {
            val parent = postsById[parentId] ?: break
            context += parent
            parentId = parent.replyToPostId ?: break
        }
        context.reverse()
        return context.toList()
    }

    internal fun detailVariables(postId: String): Map<String, Any> {
        validatePostId(postId)
        return linkedMapOf(
            "tweetId" to postId,
            "withCommunity" to false,
            "includePromotedContent" to false,
            "withVoice" to false,
        )
    }

    internal fun conversationVariables(
        postId: String,
        cursor: String?,
        rankingMode: RankingMode,
    ): Map<String, Any> {
        validatePostId(postId)
        return buildMap {
            put("focalTweetId", postId)
            put("isReaderMode", false)
            put("rankingMode", rankingMode.wireValue)
            put("includePromotedContent", false)
            put("withCommunity", true)
            put("withQuickPromoteEligibilityTweetFields", false)
            put("withBirdwatchNotes", true)
            put("withVoice", true)
            if (!cursor.isNullOrBlank()) {
                put("cursor", cursor)
            }
        }
    }

    private fun validatePostId(postId: String) {
        require(POST_ID.matches(postId)) { "ポストIDの形式が不正です。" }
    }

    private companion object {
        val POST_ID = Regex("[0-9]{1,19}")
    }
}
