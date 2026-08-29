package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.XSessionCredentials

enum class UserAction {
    FOLLOW,
    UNFOLLOW,
    MUTE,
    UNMUTE,
    BLOCK,
    UNBLOCK,
}

enum class ListMembershipAction {
    ADD,
    REMOVE,
}

fun interface UserActionExecutor {
    fun execute(account: AccountSecrets, userId: String, action: UserAction, language: String)
}

class UserActionRepository(
    private val restClient: AuthenticatedRestClient,
) : UserActionExecutor {
    override fun execute(account: AccountSecrets, userId: String, action: UserAction, language: String) {
        val request = request(userId, action)
        restClient.postForm(account, request.endpoint, request.parameters, language)
    }

    internal fun request(userId: String, action: UserAction): RestActionRequest {
        require(ID.matches(userId)) { "ユーザーIDの形式が不正です。" }
        val endpoint = when (action) {
            UserAction.FOLLOW -> "followUser"
            UserAction.UNFOLLOW -> "unfollowUser"
            UserAction.MUTE -> "muteUser"
            UserAction.UNMUTE -> "unmuteUser"
            UserAction.BLOCK -> "blockUser"
            UserAction.UNBLOCK -> "unblockUser"
        }
        return RestActionRequest(endpoint, mapOf("user_id" to userId))
    }

    internal data class RestActionRequest(
        val endpoint: String,
        val parameters: Map<String, String>,
    )
}

fun interface ListMembershipExecutor {
    fun execute(
        account: AccountSecrets,
        userId: String,
        listId: String,
        action: ListMembershipAction,
        language: String,
    )
}

class ListMembershipRepository(
    private val graphQlExecutor: GraphQlExecutor,
) : ListMembershipExecutor {
    override fun execute(
        account: AccountSecrets,
        userId: String,
        listId: String,
        action: ListMembershipAction,
        language: String,
    ) {
        val request = request(userId, listId, action)
        graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            request.purpose,
            request.variables,
            language,
        )
    }

    internal fun request(
        userId: String,
        listId: String,
        action: ListMembershipAction,
    ): GraphQlActionRequest {
        require(ID.matches(userId)) { "ユーザーIDの形式が不正です。" }
        require(ID.matches(listId)) { "リストIDの形式が不正です。" }
        return GraphQlActionRequest(
            if (action == ListMembershipAction.ADD) "listMemberAdd" else "listMemberRemove",
            mapOf("listId" to listId, "userId" to userId),
        )
    }

    internal data class GraphQlActionRequest(
        val purpose: String,
        val variables: Map<String, Any>,
    )
}

private val ID = Regex("[0-9]{1,30}")
