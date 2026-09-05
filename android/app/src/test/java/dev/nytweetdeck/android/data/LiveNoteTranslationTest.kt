package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.parseLiveNoteTranslation
import org.junit.Assert.*
import org.junit.Test

class LiveNoteTranslationTest {
    @Test fun fallsBackOnceAndReusesCompletedTranslation() {
        var notes = 0
        var live = 0
        val repository = CommunityNoteRepository(
            GraphQlExecutor { _, _, _, _ -> notes++; note },
            liveTranslation = XCommunityNoteTranslationEndpoint { _, id, target ->
                assertEquals("55", id)
                assertEquals("ja", target)
                live++
                AuthenticatedRestClient.RestResult(stream, null, null)
            },
        )
        val account = AccountSecrets("7", "7", "name", "Name", "bearer", "auth", "csrf", "profile")
        val first = repository.translateNote(account, "55", "ja")
        val again = repository.translateNote(account, "55", "ja")
        assertEquals("訳文リンク", first.translation!!.text)
        assertEquals("https://example.com/source", first.translation!!.sources.single().url)
        assertEquals(first, again)
        assertEquals(1, notes)
        assertEquals(1, live)
    }

    @Test fun rejectsEmptyAndErrorAfterPartialStream() {
        for (body in listOf("", "{\"result\":{}}", "{\"result\":{\"text\":\"partial\"}}\n{\"error\":{\"message\":\"failed\"}}")) {
            assertThrows(dev.nytweetdeck.android.model.PostTranslationException::class.java) {
                parseLiveNoteTranslation(body, "55", "en", "ja")
            }
        }
    }

    private val note = """{"data":{"birdwatch_note_by_rest_id":{"rest_id":"55","language":"en",
        "data_v1":{"summary":{"text":"Original"}},"tweet_results":{"result":{"rest_id":"123"}}}}}"""
    private val stream = """{"result":{"text":"訳文"}}
        {"result":{"text":"リンク","rich_text_entities":[{"fromIndex":2,"toIndex":5,"ref":{"url":"https://t.co/test","expandedUrl":"https://example.com/source"}}]}}""".trimIndent()
}
