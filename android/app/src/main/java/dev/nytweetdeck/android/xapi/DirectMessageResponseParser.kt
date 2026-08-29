package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.DirectMessage
import dev.nytweetdeck.android.model.DirectMessagePage
import java.util.LinkedHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** X Web DM受信箱のentries/users応答をAndroid共通モデルへ変換する。 */
class DirectMessageResponseParser(
    private val json: Json = Json,
) {
    fun parse(body: String): DirectMessagePage = try {
        val root = json.parseToJsonElement(body)
        val state = root.objectValue("inbox_initial_state") ?: root
        val users = parseUsers(state.objectValue("users"))
        val messages = state.arrayValue("entries")
            ?.mapNotNull { parseMessage(it, users) }
            ?.sortedByDescending(DirectMessage::timestamp)
            .orEmpty()

        DirectMessagePage(
            messages = messages,
            nextCursor = firstNonNull(state.text("cursor"), root.text("cursor")),
        )
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("ダイレクトメッセージ応答を解析できません。", cause = exception)
    }

    private fun parseUsers(usersNode: JsonObject?): Map<String, User> {
        val users = LinkedHashMap<String, User>()
        usersNode?.forEach { (id, value) ->
            users[id] = User(
                name = value.text("name"),
                username = value.text("screen_name"),
                avatarUrl = value.text("profile_image_url_https"),
            )
        }
        return users
    }

    private fun parseMessage(
        entry: JsonElement,
        users: Map<String, User>,
    ): DirectMessage? {
        val message = entry.objectValue("message") ?: return null
        val data = message.objectValue("message_data")
        val id = message.text("id") ?: return null
        val senderId = data.text("sender_id") ?: return null
        val text = data.text("text") ?: return null
        val sender = users[senderId]
        return DirectMessage(
            id = id,
            conversationId = message.text("conversation_id"),
            senderId = senderId,
            senderName = sender?.name,
            senderUsername = sender?.username,
            senderAvatarUrl = sender?.avatarUrl,
            text = text,
            timestamp = message.longValue("time"),
        )
    }

    private data class User(
        val name: String?,
        val username: String?,
        val avatarUrl: String?,
    )

    private fun JsonElement?.objectValue(name: String): JsonObject? =
        (this as? JsonObject)?.get(name) as? JsonObject

    private fun JsonElement?.arrayValue(name: String): JsonArray? =
        (this as? JsonObject)?.get(name) as? JsonArray

    private fun JsonElement?.text(name: String): String? =
        ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.longValue(name: String): Long =
        ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.longOrNull ?: 0L

    private fun firstNonNull(first: String?, second: String?): String? = first ?: second
}
