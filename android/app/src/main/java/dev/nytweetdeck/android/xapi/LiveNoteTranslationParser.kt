package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.PostTranslation
import dev.nytweetdeck.android.model.PostTranslationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** X emits newline-delimited text chunks and final rich-text link entities. */
internal fun parseLiveNoteTranslation(body: String, noteId: String, source: String?, target: String): PostTranslation {
    return parseLiveTranslation(body, "note:$noteId", source, target)
}

internal fun parseLiveTranslation(body: String, contentId: String, source: String?, target: String): PostTranslation {
    val text = StringBuilder()
    val entities = mutableListOf<JsonElement>()
    try {
        body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val chunk = Json.parseToJsonElement(line) as? JsonObject ?: error("Invalid translation chunk")
            if (chunk["error"] != null && chunk["error"] != JsonNull) error("Translation stream failed")
            val result = chunk["result"] as? JsonObject ?: return@forEach
            (result["text"] as? JsonPrimitive)?.contentOrNull?.let(text::append)
            (result["rich_text_entities"] as? JsonArray)?.let(entities::addAll)
        }
        check(text.isNotBlank()) { "Empty translation" }
    } catch (failure: Exception) {
        throw PostTranslationException("Xライブ翻訳応答を解析できません。", 502, cause = failure)
    }
    return PostTranslation(contentId, source.orEmpty(), target, text.toString(),
        sources = parseNoteSources(text.toString(), JsonArray(entities)))
}
