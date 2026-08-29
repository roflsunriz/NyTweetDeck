package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.Trend
import dev.nytweetdeck.android.model.TrendPage
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** X GraphQLのExplore応答からトレンドとページング情報を抽出する。 */
class TrendResponseParser(
    private val json: Json = Json,
) {
    fun parse(body: String): TrendPage = try {
        val root = json.parseToJsonElement(body)
        val trends = LinkedHashMap<String, Trend>()
        val cursor = CursorHolder()
        visit(root, trends, cursor)
        TrendPage(trends.values.toList(), cursor.value)
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("トレンド応答を解析できません。", cause = exception)
    }

    private fun visit(
        node: JsonElement?,
        trends: MutableMap<String, Trend>,
        cursor: CursorHolder,
    ) {
        when (node) {
            is JsonArray -> node.forEach { visit(it, trends, cursor) }
            is JsonObject -> {
                findCursor(node, cursor)
                if (isTrend(node)) {
                    val trend = parseTrend(node)
                    trends.putIfAbsent(trend.name, trend)
                    return
                }
                node.values.forEach { visit(it, trends, cursor) }
            }
            else -> Unit
        }
    }

    private fun isTrend(node: JsonObject): Boolean =
        node.text("name") != null &&
            node.objectValue("trend_url") != null &&
            node.objectValue("trend_metadata") != null

    private fun parseTrend(node: JsonObject): Trend {
        val name = requireNotNull(node.text("name"))
        val metadata = requireNotNull(node.objectValue("trend_metadata"))
        val url = node.objectValue("trend_url").text("url")
        return Trend(
            name = name,
            description = node.text("description"),
            rank = node.text("rank"),
            url = safeUrl(url, name),
            domainContext = metadata.text("domain_context"),
            metaDescription = null,
        )
    }

    private fun safeUrl(value: String?, name: String): String {
        if (value != null) {
            try {
                val uri = URI.create(value)
                val host = uri.host?.lowercase(Locale.ROOT)
                val safeHost = host != null && (
                    host == "x.com" ||
                        host.endsWith(".x.com") ||
                        host == "twitter.com" ||
                        host.endsWith(".twitter.com")
                    )
                if (uri.scheme.equals("https", ignoreCase = true) &&
                    uri.userInfo == null &&
                    (uri.port == -1 || uri.port == 443) &&
                    safeHost
                ) {
                    return value
                }
            } catch (_: IllegalArgumentException) {
                // 安全な検索URLへフォールバックする。
            }
        }
        return "https://x.com/search?q=" +
            URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

    private fun findCursor(node: JsonObject, cursor: CursorHolder) {
        val cursorType = firstNonNull(node.text("cursorType"), node.text("cursor_type"))
        if (cursorType.equals("Bottom", ignoreCase = true)) {
            cursor.value = node.text("value")
            return
        }
        val entryId = firstNonNull(node.text("entryId"), node.text("entry_id"))
        if (entryId?.lowercase(Locale.ROOT)?.contains("cursor-bottom") == true) {
            node.objectValue("content")?.text("value")?.let { cursor.value = it }
        }
    }

    private fun JsonObject?.text(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.objectValue(name: String): JsonObject? =
        this?.get(name) as? JsonObject

    private fun firstNonNull(first: String?, second: String?): String? = first ?: second

    private class CursorHolder {
        var value: String? = null
    }
}
