package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.ListDirectoryPage
import dev.nytweetdeck.android.model.ListOption
import java.util.LinkedHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** X Web APIのリスト一覧応答をカラム候補へ正規化する。 */
class ListDirectoryParser(
    private val json: Json = Json,
) {
    fun parse(
        body: String,
        source: String,
    ): ListDirectoryPage = try {
        val root = json.parseToJsonElement(body)
        val lists = LinkedHashMap<String, ListOption>()
        val cursor = CursorHolder()
        visit(root, source, lists, cursor)
        ListDirectoryPage(lists.values.toList(), cursor.value)
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("Xリスト一覧を解析できません。", cause = exception)
    }

    private fun visit(
        node: JsonElement?,
        source: String,
        lists: MutableMap<String, ListOption>,
        cursor: CursorHolder,
    ) {
        when (node) {
            is JsonArray -> node.forEach { visit(it, source, lists, cursor) }
            is JsonObject -> {
                findCursor(node, cursor)
                parseList(node, source)?.let { list ->
                    lists.putIfAbsent(list.id, list)
                    return
                }
                node.values.forEach { visit(it, source, lists, cursor) }
            }

            else -> Unit
        }
    }

    private fun findCursor(
        node: JsonObject,
        cursor: CursorHolder,
    ) {
        if (node.text("cursorType").equals("Bottom", ignoreCase = true)) {
            cursor.value = node.text("value")
        }
    }

    private fun parseList(
        node: JsonObject,
        source: String,
    ): ListOption? {
        if (node.text("__typename") != "TimelineTwitterList") {
            return null
        }
        val list = node.objectValue("list") ?: return null
        val id = list.text("id_str") ?: return null
        val name = list.text("name") ?: return null
        val ownerCore = list.objectValue("user_results")
            .objectValue("result")
            .objectValue("core")
        return ListOption(
            id = id,
            name = name,
            description = list.text("description"),
            ownerName = ownerCore.text("name"),
            ownerUsername = ownerCore.text("screen_name"),
            memberCount = list.longValue("member_count"),
            subscriberCount = list.longValue("subscriber_count"),
            source = source,
        )
    }

    private fun JsonObject?.text(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.objectValue(name: String): JsonObject? =
        this?.get(name) as? JsonObject

    private fun JsonObject?.longValue(name: String): Long =
        (this?.get(name) as? JsonPrimitive)?.longOrNull ?: 0L

    private class CursorHolder {
        var value: String? = null
    }
}
