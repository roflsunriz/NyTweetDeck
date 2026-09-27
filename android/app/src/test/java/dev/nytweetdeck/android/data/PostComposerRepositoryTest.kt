package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.ComposerPlace
import dev.nytweetdeck.android.model.ComposerPoll
import dev.nytweetdeck.android.model.ComposerSubmission
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import java.time.Instant
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

    @Test
    fun deletesOnlyValidatedCreatedPostIds() {
        var purpose: String? = null
        var variables: Map<String, Any?>? = null
        val repository = PostComposerRepository(GraphQlExecutor { _, value, input, _ ->
            purpose = value
            variables = input
            "{}"
        })

        repository.delete(account(), "123")

        assertEquals("deletePost", purpose)
        assertEquals(mapOf("tweet_id" to "123"), variables)
        assertThrows(IllegalArgumentException::class.java) {
            repository.delete(account(), "../123")
        }
    }

    @Test
    fun buildsMediaPlaceAndDisclosureVariablesForTheXWebMutation() {
        val submission = ComposerSubmission(
            text = " image ",
            place = ComposerPlace("abc123", "Tokyo", "Japan", "request1"),
            paidPartnership = true,
            aiGenerated = true,
        )
        val variables = repository.advancedVariables(submission, listOf("123"), null)

        assertEquals("image", variables["tweet_text"])
        assertEquals(mapOf("place_id" to "abc123", "geo_search_request_id" to "request1"), variables["geo"])
        assertEquals(mapOf(
            "media_entities" to listOf(mapOf("media_id" to "123", "tagged_users" to emptyList<String>())),
            "possibly_sensitive" to false,
        ), variables["media"])
        assertEquals(mapOf(
            "advertising_disclosure" to mapOf("is_paid_promotion" to true),
            "ai_generated_disclosure" to mapOf(
                "has_ai_generated_media" to true,
                "ai_generated_detection_source" to "UserDeclared",
            ),
        ), variables["content_disclosure"])
    }

    @Test
    fun schedulesWithUnixSecondsAndRejectsPollCombiningWithSchedule() {
        val at = Instant.now().plusSeconds(7_200)
        val scheduled = ComposerSubmission(text = "later", scheduledAt = at, paidPartnership = true)
        val variables = repository.scheduledVariables(scheduled, emptyList())
        assertEquals(at.epochSecond, variables["execute_at"])
        assertEquals(mapOf(
            "status" to "later",
            "media_ids" to emptyList<String>(),
            "exclude_reply_user_ids" to emptyList<String>(),
            "thread_tweets" to emptyList<Any>(),
            "content_disclosure_options" to mapOf(
                "advertising_disclosure" to mapOf("is_paid_promotion" to true),
            ),
        ), variables["post_tweet_request"])

        assertThrows(IllegalArgumentException::class.java) {
            repository.scheduledVariables(
                ComposerSubmission("poll", poll = ComposerPoll(listOf("yes", "no"), 60), scheduledAt = at),
                emptyList(),
            )
        }
    }

    @Test
    fun scheduleMutationMustReturnAConfirmedId() {
        val at = Instant.now().plusSeconds(7_200)
        val submission = ComposerSubmission("later", scheduledAt = at)
        val confirmed = PostComposerRepository(GraphQlExecutor { _, purpose, _, _ ->
            assertEquals("schedulePost", purpose)
            "{\"data\":{\"tweet\":{\"rest_id\":\"123\"}}}"
        })
        assertEquals(null, confirmed.submitAdvanced(account(), submission))

        val missing = PostComposerRepository(GraphQlExecutor { _, _, _, _ -> "{}" })
        assertThrows(IllegalArgumentException::class.java) {
            missing.submitAdvanced(account(), submission)
        }
    }

    private fun account() = AccountSecrets(
        "7", "7", "fixture", "Fixture", "bearer", "auth", "csrf", "profile",
    )
}
