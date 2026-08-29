package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PostComposerRepositoryTest {
    private val repository = PostComposerRepository(GraphQlExecutor { _, _, _, _ -> "{}" })

    @Test
    fun createsPlainReplyAndQuoteVariables() {
        assertEquals("hello", repository.variables("  hello  ")["tweet_text"])
        assertEquals(
            mapOf("in_reply_to_tweet_id" to "123", "exclude_reply_user_ids" to emptyList<String>()),
            repository.variables("reply", replyToPostId = "123")["reply"],
        )
        assertEquals(
            "https://twitter.com/i/status/456",
            repository.variables("quote", quotePostId = "456")["attachment_url"],
        )
    }

    @Test
    fun enforcesTextAndTargetBoundaries() {
        listOf("", "   ", "x".repeat(4001)).forEach { invalidText ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.variables(invalidText)
            }
        }
        assertEquals(4000, (repository.variables("x".repeat(4000))["tweet_text"] as String).length)
        assertThrows(IllegalArgumentException::class.java) {
            repository.variables("text", replyToPostId = "bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.variables("text", replyToPostId = "1", quotePostId = "2")
        }
    }
}
