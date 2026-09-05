package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.CommunityNote
import dev.nytweetdeck.android.ui.isCommunityNoteTranslationCandidate
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.NotificationResponseParser
import org.junit.Assert.*
import org.junit.Test

class CommunityNoteTranslationTest {
    @Test fun validatesDestinationAndUsesTranslatedSourceOffsets() {
        val parser = NotificationResponseParser()
        val note = parser.parseCommunityNote(response(), "55", "ja")
        assertEquals("Original source", note.text)
        assertEquals("翻訳 出典", note.translation?.text)
        assertEquals(3, note.translation?.sources?.single()?.fromIndex)
        assertEquals("https://example.com/translated", note.translation?.sources?.single()?.url)
        assertNull(parser.parseCommunityNote(response(), "55", "fr").translation)
        assertNull(parser.parseCommunityNote(response().replace("\"is_available\":true", "\"is_available\":false"), "55", "ja").translation)
        assertTrue(parser.parseCommunityNote(response().replace("https://example.com/translated", "javascript:alert(1)")
            .replace("https://t.co/translated", "javascript:bad"), "55", "ja").translation!!.sources.isEmpty())
    }

    @Test fun cacheSeparatesAccountAndLanguageAndUsesOnlyNoteEndpoint() {
        val calls = mutableListOf<String>()
        val repository = CommunityNoteRepository(GraphQlExecutor { _, purpose, variables, language ->
            assertEquals("communityNote", purpose)
            assertEquals("55", variables["note_id"])
            calls += language
            response().replace("\"destination_language\":\"ja\"", "\"destination_language\":\"$language\"")
        })
        val account = AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile")
        repository.loadNote(account, "55", "ja")
        repository.loadNote(account, "55", "ja")
        repository.loadNote(account, "55", "fr")
        repository.loadNote(account.copy(accountId = "8"), "55", "ja")
        assertEquals(listOf("ja", "fr", "ja"), calls)
    }

    @Test fun guardUsesActualTargetAndSkipsEmptyBody() {
        val note = CommunityNote(null, "Original", null, "55", "en", false)
        assertTrue(isCommunityNoteTranslationCandidate(note, "ja"))
        assertFalse(isCommunityNoteTranslationCandidate(note, "en-US"))
        assertFalse(isCommunityNoteTranslationCandidate(note.copy(text = "@alice https://t.co/image"), "ja"))
        assertTrue(isCommunityNoteTranslationCandidate(note.copy(language = null), "ja"))
        assertTrue(isCommunityNoteTranslationCandidate(note.copy(language = null, isTranslatable = true), "ja"))
    }

    private fun response() = """
        {"data":{"birdwatch_note_by_rest_id":{"rest_id":"55","language":"en",
        "is_community_note_translatable":true,"data_v1":{"summary":{"text":"Original source",
        "entities":[{"fromIndex":9,"toIndex":15,"ref":{"url":"https://example.com/original"}}]}},
        "tweet_results":{"result":{"rest_id":"123"}},
        "grok_translated_community_note_with_availability":{"is_available":true,"data":{
        "destination_language":"ja","source_language":"en","translation_available":true,
        "translation":"翻訳 出典","rich_text_entities":[{"from_index":"3","to_index":"5",
        "ref":{"url":"https://t.co/translated","expanded_url":"https://example.com/translated","type":"TimelineUrl"}}]}}}}}
    """.trimIndent()
}
