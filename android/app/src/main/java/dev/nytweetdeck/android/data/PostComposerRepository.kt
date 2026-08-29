package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class PostComposerRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: TimelineResponseParser = TimelineResponseParser(),
) {
    fun submit(
        account: AccountSecrets,
        text: String,
        replyToPostId: String? = null,
        quotePostId: String? = null,
        language: String = "ja",
    ): Post {
        val variables = variables(text, replyToPostId, quotePostId)
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "createPost",
            variables,
            language,
        )
        return parser.parse(body).posts.firstOrNull()
            ?: error("投稿応答に作成済みポストがありません。")
    }

    fun delete(account: AccountSecrets, postId: String, language: String = "ja") {
        require(POST_ID.matches(postId)) { "削除対象ポストIDの形式が不正です。" }
        graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "deletePost",
            mapOf("tweet_id" to postId),
            language,
        )
    }

    internal fun variables(
        text: String,
        replyToPostId: String? = null,
        quotePostId: String? = null,
    ): Map<String, Any> {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty() && normalizedText.length <= MAX_POST_LENGTH) {
            "ポスト本文は1〜4000文字で指定してください。"
        }
        require(replyToPostId == null || POST_ID.matches(replyToPostId)) {
            "返信先ポストIDの形式が不正です。"
        }
        require(quotePostId == null || POST_ID.matches(quotePostId)) {
            "引用先ポストIDの形式が不正です。"
        }
        require(replyToPostId == null || quotePostId == null) {
            "返信と引用は同時に指定できません。"
        }
        return buildMap {
            put("tweet_text", normalizedText)
            put("nullcast", false)
            put("includeCommunityTweetRelationship", false)
            put("includeTweetVisibilityNudge", true)
            replyToPostId?.let { postId ->
                put(
                    "reply",
                    mapOf(
                        "in_reply_to_tweet_id" to postId,
                        "exclude_reply_user_ids" to emptyList<String>(),
                    ),
                )
            }
            quotePostId?.let { postId ->
                put("attachment_url", "https://twitter.com/i/status/$postId")
            }
        }
    }

    private companion object {
        const val MAX_POST_LENGTH = 4000
        val POST_ID = Regex("[0-9]{1,19}")
    }
}
