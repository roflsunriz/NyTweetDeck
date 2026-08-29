package dev.nytweetdeck.android.xapi

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationResponseParserTest {
    private val parser = NotificationResponseParser()

    @Test
    fun normalizesRelatedPostsAndCursorFromTheExistingTimelineFixture() {
        val page = parser.parse(fixture("timeline-response.json"))

        assertTrue(page.notifications.isEmpty())
        assertEquals(listOf("101", "100"), page.posts.map { it.id })
        assertEquals("next-cursor", page.nextCursor)
    }

    @Test
    fun parsesConfirmedWebTimelineNotificationAndSanitizesImages() {
        val notification = parser.parse(
            """
            {"entries":[{"content":{"notification":{"id":"follow-1","notification_icon":"person",
            "url":{"url":"twitter://user?screen_name=alice","url_type":"DeepLink"},
            "socialContext":{"generalContext":{"text":"Alice followed you","contextImageUrls":[
            "https://pbs.twimg.com/alice.jpg","javascript:bad","http://pbs.twimg.com/insecure.jpg",
            "https://pbs.twimg.com.evil/alice.jpg"]}}}}}]}
            """.trimIndent(),
        ).notifications.single()

        assertEquals("follow-1", notification.id)
        assertEquals("follow", notification.kind)
        assertEquals("Alice followed you", notification.text)
        assertNull(notification.postId)
        assertEquals(1, notification.actors.size)
        assertNull(notification.actors.single().id)
        assertEquals("alice", notification.actors.single().username)
        assertEquals(listOf("https://pbs.twimg.com/alice.jpg"), notification.imageUrls)
    }

    @Test
    fun collectsEveryEmbeddedFollowerAndDeduplicatesActors() {
        val notification = parser.parse(
            """
            {"notification":{"id":"follow-many","notification_icon":"person",
            "message":{"text":"Alice and Bob followed you"},"template":{"actors":[
            {"user_results":{"result":{"__typename":"User","rest_id":"42",
            "core":{"screen_name":"alice","name":"Alice"},
            "avatar":{"image_url":"https://pbs.twimg.com/alice.jpg"}}}},
            {"user_results":{"result":{"__typename":"User","rest_id":"42",
            "core":{"screen_name":"alice","name":"Alice duplicate"},
            "avatar":{"image_url":"https://pbs.twimg.com/alice-duplicate.jpg"}}}},
            {"user_results":{"result":{"__typename":"User","rest_id":"84",
            "core":{"screen_name":"bob","name":"Bob"},
            "avatar":{"image_url":"https://pbs.twimg.com/bob.jpg"}}}}]}}}
            """.trimIndent(),
        ).notifications.single()

        assertEquals("follow", notification.kind)
        assertEquals(listOf("42", "84"), notification.actors.map { it.id })
        assertEquals(listOf("alice", "bob"), notification.actors.map { it.username })
        assertEquals("Alice", notification.actors.first().displayName)
    }

    @Test
    fun classifiesLikeAndLinksItsTargetPostInsideNyTweetDeck() {
        val notification = parser.parse(
            """
            {"notification":{"id":"like-1","notification_icon":"heart",
            "message":{"text":"Alice liked your post"},
            "url":{"url":"https://x.com/alice/status/123"},
            "template":{"target":{"__typename":"Tweet","rest_id":"123"},
            "actor":{"profile_image_url_https":"https://pbs.twimg.com/alice.jpg"}}}}
            """.trimIndent(),
        ).notifications.single()

        assertEquals("like", notification.kind)
        assertEquals("123", notification.postId)
        assertEquals(listOf("https://pbs.twimg.com/alice.jpg"), notification.imageUrls)
    }

    @Test
    fun linksACommunityNoteFromItsNestedWebDeepLink() {
        val notification = parser.parse(
            """
            {"notification":{"id":"community-1","notification_icon":"birdwatch",
            "socialContext":{"generalContext":{"text":"Community Note added"}},
            "rich_message":{"text":"Readers added context to this post.","entities":[
            {"ref":{"type":"TimelineUrl","url":"twitter://tweet?id=987"}}]}}}
            """.trimIndent(),
        ).notifications.single()

        assertEquals("community_note", notification.kind)
        assertEquals("Community Note added", notification.text)
        assertNull(notification.noteId)
        assertEquals("987", notification.postId)
    }

    @Test
    fun extractsTheActualNoteIdAndTargetPostFromCurrentTimelineNotification() {
        val notification = parser.parse(
            """
            {"notification":{"id":"community-current","notification_icon":"birdwatch_note",
            "notification_social_context":{"text":"A Community Note was added"},
            "notification_url":{"url":"https://twitter.com/i/birdwatch/n/555?src=notification"},
            "rich_message":{"text":"A Community Note was added to a post you interacted with."},
            "template":{"additional_context":{"text":"This is the complete note body with a source.",
            "entities":[{"fromIndex":42,"toIndex":48,"ref":{"type":"TimelineUrl",
            "url":"https://x.com/alice/status/987"}}]}}}}
            """.trimIndent(),
        ).notifications.single()

        assertEquals("community_note", notification.kind)
        assertEquals("555", notification.noteId)
        assertEquals("987", notification.postId)
    }

    @Test
    fun deduplicatesNotificationsByIdInResponseOrder() {
        val page = parser.parse(
            """
            {"notifications":[
            {"id":"same","notification_icon":"heart","message":{"text":"first"}},
            {"id":"same","notification_icon":"follow","message":{"text":"second"}}]}
            """.trimIndent(),
        )

        assertEquals(1, page.notifications.size)
        assertEquals("first", page.notifications.single().text)
        assertEquals("like", page.notifications.single().kind)
    }

    @Test
    fun parsesTheCompleteNoteBodyAndValidatedSourceRanges() {
        val note = parser.parseCommunityNote(
            """
            {"data":{"birdwatch_note_by_rest_id":{"rest_id":"555","data_v1":{"summary":{
            "text":"Context with source","entities":[
            {"fromIndex":13,"toIndex":19,"ref":{"url":"https://example.com/source"}},
            {"from_index":0,"to_index":7,"ref":{"url":"javascript:bad"}},
            {"fromIndex":50,"toIndex":80,"ref":{"url":"https://example.com/outside"}}
            ]}},"tweet_results":{"result":{"rest_id":"987","media_note_category":"none"}}}}}
            """.trimIndent(),
            "555",
        )

        assertEquals("555", note.noteId)
        assertEquals("Context with source", note.text)
        assertEquals(1, note.sources.size)
        assertEquals(13, note.sources.single().fromIndex)
        assertEquals(19, note.sources.single().toIndex)
        assertEquals("https://example.com/source", note.sources.single().url)
        assertEquals("987", note.targetPostId)
    }

    @Test
    fun rejectsACommunityNoteResponseForAnotherNote() {
        val exception = assertThrows(XApiException::class.java) {
            parser.parseCommunityNote(
                """
                {"data":{"birdwatch_note_by_rest_id":{"rest_id":"999",
                "data_v1":{"summary":{"text":"wrong note"}}}}}
                """.trimIndent(),
                "555",
            )
        }

        assertEquals("コミュニティノート応答を解析できません。", exception.message)
        assertNotNull(exception.cause)
    }

    @Test
    fun convertsMalformedJsonToXApiException() {
        val exception = assertThrows(XApiException::class.java) {
            parser.parse("{not-json")
        }

        assertEquals("通知応答を解析できません。", exception.message)
        assertNotNull(exception.cause)
    }

    @Test
    fun returnsAnEmptyPageForNonNotificationPayload() {
        val page = parser.parse("""{"data":{"viewer":null}}""")

        assertTrue(page.notifications.isEmpty())
        assertTrue(page.posts.isEmpty())
        assertNull(page.nextCursor)
        assertFalse(page.notifications.any { it.id.isBlank() })
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/$name"),
    ).use { input ->
        String(input.readBytes(), StandardCharsets.UTF_8)
    }
}
