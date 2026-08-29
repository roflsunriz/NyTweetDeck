package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.XSessionCredentials

class PostActionRepository(
    private val graphQlExecutor: GraphQlExecutor,
) {
    fun setActive(
        account: AccountSecrets,
        postId: String,
        action: PostActionType,
        active: Boolean,
        language: String = "ja",
    ) {
        require(POST_ID.matches(postId)) { "ポストIDの形式が不正です。" }
        val request = request(postId, action, active)
        graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            request.purpose,
            request.variables,
            language,
        )
    }

    internal fun request(postId: String, action: PostActionType, active: Boolean): ActionRequest {
        require(POST_ID.matches(postId)) { "ポストIDの形式が不正です。" }
        return when (action) {
            PostActionType.LIKE -> ActionRequest(
                if (active) "like" else "unlike",
                mapOf("tweet_id" to postId),
            )
            PostActionType.REPOST -> if (active) {
                ActionRequest("repost", mapOf("tweet_id" to postId, "dark_request" to false))
            } else {
                ActionRequest("undoRepost", mapOf("source_tweet_id" to postId))
            }
            PostActionType.BOOKMARK -> ActionRequest(
                if (active) "bookmark" else "removeBookmark",
                mapOf("tweet_id" to postId),
            )
        }
    }

    internal data class ActionRequest(
        val purpose: String,
        val variables: Map<String, Any>,
    )

    private companion object {
        val POST_ID = Regex("[0-9]{1,30}")
    }
}
