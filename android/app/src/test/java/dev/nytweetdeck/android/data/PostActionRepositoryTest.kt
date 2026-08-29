package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PostActionRepositoryTest {
    private val repository = PostActionRepository(GraphQlExecutor { _, _, _, _ -> "{}" })

    @Test
    fun mapsEveryToggleStateToTheMatchingXMutation() {
        assertEquals("like", repository.request("123", PostActionType.LIKE, true).purpose)
        assertEquals("unlike", repository.request("123", PostActionType.LIKE, false).purpose)
        assertEquals("repost", repository.request("123", PostActionType.REPOST, true).purpose)
        assertEquals(
            mapOf("source_tweet_id" to "123"),
            repository.request("123", PostActionType.REPOST, false).variables,
        )
        assertEquals("bookmark", repository.request("123", PostActionType.BOOKMARK, true).purpose)
        assertEquals(
            "removeBookmark",
            repository.request("123", PostActionType.BOOKMARK, false).purpose,
        )
    }

    @Test
    fun rejectsInvalidPostIdsBeforeNetworkAccess() {
        listOf("", "abc", "1/2", "1".repeat(31)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.request(invalid, PostActionType.LIKE, true)
            }
        }
    }
}
