package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.XSessionCredentials

enum class UserAction {
    FOLLOW,
    MUTE,
    BLOCK,
}

enum class ListMembershipAction {
    ADD,
    REMOVE,
}

class UserActionRepository(
    private val restClient: AuthenticatedRestClient,
) {
    fun execute(account: AccountSecrets, userId: String, action: UserAction, language: String = "ja") {
        val request = request(userId, action)
        restClient.postForm(account, request.endpoint, request.parameters, language)
    }

    internal fun request(userId: String, action: UserAction): RestActionRequest {
        require(ID.matches(userId)) { "ユーザーIDの形式が不正です。" }
        val endpoint = when (action) {
            UserAction.FOLLOW -> "followUser"
            UserAction.MUTE -> "muteUser"
            UserAction.BLOCK -> "blockUser"
        }
        return RestActionRequest(endpoint, mapOf("user_id" to userId))
    }

    internal data class RestActionRequest(
        val endpoint: String,
        val parameters: Map<String, String>,
    )
}

class ListMembershipRepository(
    private val graphQlExecutor: GraphQlExecutor,
) {
    fun execute(
        account: AccountSecrets,
        userId: String,
        listId: String,
        action: ListMembershipAction,
        language: String = "ja",
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
            mapOf("list_id" to listId, "user_id" to userId),
        )
    }

    internal data class GraphQlActionRequest(
        val purpose: String,
        val variables: Map<String, Any>,
    )
}

private val ID = Regex("[0-9]{1,30}")
