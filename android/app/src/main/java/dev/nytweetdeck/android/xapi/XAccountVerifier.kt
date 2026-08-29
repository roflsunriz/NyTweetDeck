package dev.nytweetdeck.android.xapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class VerifiedXAccount(
    val userId: String,
    val username: String,
    val displayName: String,
)

class XAccountVerifier(
    private val graphQlClient: AuthenticatedGraphQlClient,
) {
    fun verify(
        credentials: XSessionCredentials,
        expectedUserId: String,
        language: String,
    ): VerifiedXAccount {
        require(expectedUserId.matches(Regex("[0-9]{1,24}"))) { "XユーザーID形式が不正です。" }
        val body = graphQlClient.execute(
            credentials = credentials,
            purpose = "userByRestId",
            variables = mapOf("userId" to expectedUserId),
            language = language,
        )
        val root = try {
            Json.parseToJsonElement(body)
        } catch (exception: Exception) {
            throw XApiException("Xアカウント応答を解析できません。", cause = exception)
        }
        val user = findUser(root, expectedUserId)
            ?: throw XApiException("ログイン中のXアカウントを確認できません。", 401)
        val core = user["core"] as? JsonObject
        val legacy = user["legacy"] as? JsonObject
        val username = core.text("screen_name") ?: legacy.text("screen_name")
        val displayName = core.text("name") ?: legacy.text("name") ?: username
        if (username == null || !username.matches(Regex("[A-Za-z0-9_]{1,15}"))) {
            throw XApiException("Xユーザー名形式が不正です。", 502)
        }
        return VerifiedXAccount(expectedUserId, username, displayName?.take(100) ?: username)
    }

    internal companion object {
        fun findUser(element: JsonElement, expectedUserId: String): JsonObject? {
            when (element) {
                is JsonObject -> {
                    if (element.text("__typename") == "User" && element.text("rest_id") == expectedUserId) {
                        return element
                    }
                    element.values.forEach { value -> findUser(value, expectedUserId)?.let { return it } }
                }
                is JsonArray -> element.forEach { value -> findUser(value, expectedUserId)?.let { return it } }
                else -> Unit
            }
            return null
        }

        private fun JsonObject?.text(name: String): String? =
            (this?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}
