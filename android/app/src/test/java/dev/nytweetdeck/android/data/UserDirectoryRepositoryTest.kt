package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.model.UserProfileTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UserDirectoryRepositoryTest {
    @Test
    fun stripsAtSignAndResolvesCurrentUserSchema() {
        var screenName: Any? = null
        val repository = UserDirectoryRepository(
            GraphQlExecutor { _, _, variables, _ ->
                screenName = variables["screen_name"]
                """{"data":{"user":{"result":{"__typename":"User","rest_id":"42",
                "core":{"screen_name":"alice","name":"Alice"}}}}}"""
            },
        )

        val result = repository.resolve(account(), " @alice ")

        assertEquals("alice", screenName)
        assertEquals("42", result.id)
        assertThrows(IllegalArgumentException::class.java) { repository.resolve(account(), "not valid!") }
    }

    @Test
    fun loadsProfileAndUsesTheDedicatedRepliesTimeline() {
        val purposes = mutableListOf<String>()
        val repository = UserDirectoryRepository(
            GraphQlExecutor { _, purpose, _, _ ->
                purposes += purpose
                when (purpose) {
                    "userByRestId" -> """{"data":{"user":{"result":{"__typename":"User","rest_id":"42",
                        "core":{"screen_name":"alice","name":"Alice"}}}}}"""
                    "followersYouKnow" -> "{}"
                    "userReplies" -> """{"entries":[{"entryId":"tweet-100","content":{"itemContent":
                        {"tweet_results":{"result":{"__typename":"Tweet","rest_id":"100",
                        "legacy":{"full_text":"reply"}}}}}}]}"""
                    else -> error("unexpected purpose: $purpose")
                }
            },
        )

        val profile = repository.profile(account(), "42")
        val replies = repository.timeline(account(), "42", UserProfileTab.REPLIES)

        assertEquals("alice", profile.username)
        assertEquals(listOf("100"), replies.posts.map { it.id })
        assertEquals(listOf("userByRestId", "followersYouKnow", "userReplies"), purposes)
    }

    private fun account() = AccountSecrets(
        "7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7",
    )
}
