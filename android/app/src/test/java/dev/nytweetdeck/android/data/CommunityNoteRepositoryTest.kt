package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunityNoteRepositoryTest {
    @Test
    fun loadsCompleteNoteSourcesAndMatchingPost() {
        val purposes = mutableListOf<String>()
        val repository = CommunityNoteRepository(GraphQlExecutor { _, purpose, _, _ ->
            purposes += purpose
            if (purpose == "communityNote") noteResponse() else postResponse()
        })

        val page = repository.load(account(), "55")

        assertEquals(listOf("communityNote", "postDetail"), purposes)
        assertEquals("55", page.detail.noteId)
        assertEquals("Helpful source", page.detail.text)
        assertEquals("https://example.com/source", page.detail.sources.single().url)
        assertEquals("123", page.post.id)
    }

    @Test
    fun rejectsInvalidNoteIdBeforeNetwork() {
        var calls = 0
        val repository = CommunityNoteRepository(GraphQlExecutor { _, _, _, _ -> calls++; "{}" })

        assertThrows(IllegalArgumentException::class.java) { repository.load(account(), "bad") }
        assertEquals(0, calls)
    }

    private fun account() = AccountSecrets(
        "7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile",
    )

    private fun noteResponse(): String = """
        {"data":{"birdwatch_note_by_rest_id":{"rest_id":"55","data_v1":{"summary":{
        "text":"Helpful source","entities":[{"fromIndex":0,"toIndex":7,
        "ref":{"url":"https://example.com/source"}}]}},"tweet_results":{"result":
        {"__typename":"Tweet","rest_id":"123","legacy":{"full_text":"target"}}}}}}
    """.trimIndent()

    private fun postResponse(): String = """
        {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"123",
        "legacy":{"full_text":"target"}}}}}
    """.trimIndent()
}
