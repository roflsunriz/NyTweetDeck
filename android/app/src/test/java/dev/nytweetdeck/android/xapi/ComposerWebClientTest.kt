package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.ComposerPoll
import dev.nytweetdeck.android.model.ComposerPlace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposerWebClientTest {
    @Test
    fun makesTheCurrentXTextPollCard() {
        val card = ComposerWebClient.pollCardData(ComposerPoll(listOf(" yes ", "no"), 60))
        assertEquals("poll2choice_text_only", card["twitter:card"]?.jsonPrimitive?.content)
        assertEquals("yes", card["twitter:string:choice1_label"]?.jsonPrimitive?.content)
        assertEquals("60", card["twitter:long:duration_minutes"]?.jsonPrimitive?.content)
    }

    @Test
    fun acceptsOnlyJpegPngAndGifSignatures() {
        assertEquals("image/jpeg", ComposerWebClient.mediaType(byteArrayOf(-1, -40, -1, 1, 2, 3)))
        assertEquals("image/png", ComposerWebClient.mediaType(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("image/gif", ComposerWebClient.mediaType("GIF89a".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            ComposerWebClient.mediaType("not-an-image".toByteArray())
        }
    }

    @Test
    fun readsTheCurrentComposePlaceResponseIncludingItsOptionalRequestId() {
        val response = Json.parseToJsonElement("""
            {"places":[
              {"place":{"id":"abc123","full_name":"Tokyo, Japan","country":"Japan"},
               "geo_search_request_id":"request1"},
              {"place":{"id":"def456","full_name":"Osaka, Japan","country":"Japan"}}
            ]}
        """).jsonObject

        assertEquals(listOf(
            ComposerPlace("abc123", "Tokyo, Japan", "Japan", "request1"),
            ComposerPlace("def456", "Osaka, Japan", "Japan"),
        ), ComposerWebClient.parsePlaces(response))
    }
}
