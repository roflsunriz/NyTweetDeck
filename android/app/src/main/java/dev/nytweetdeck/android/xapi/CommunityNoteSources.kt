package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.CommunityNoteSource
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun parseNoteSources(text: String, entities: JsonElement?): List<CommunityNoteSource> =
    (entities as? JsonArray).orEmpty().mapNotNull { entity ->
        val source = entity as? JsonObject ?: return@mapNotNull null
        fun value(objectValue: JsonObject, key: String) = (objectValue[key] as? JsonPrimitive)?.contentOrNull
        val ref = source["ref"] as? JsonObject ?: return@mapNotNull null
        fun safe(url: String?): Boolean = runCatching {
            val uri = URI(url.orEmpty())
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
        val url = value(ref, "expanded_url")?.takeIf(::safe)
            ?: value(ref, "url")?.takeIf(::safe) ?: return@mapNotNull null
        val from = (value(source, "from_index") ?: value(source, "fromIndex"))?.toIntOrNull()
            ?: return@mapNotNull null
        val to = (value(source, "to_index") ?: value(source, "toIndex"))?.toIntOrNull()
            ?: return@mapNotNull null
        if (from < 0 || to <= from || to > text.length) null else CommunityNoteSource(from, to, url)
    }
