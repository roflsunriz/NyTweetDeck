package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectMessageResponseParserTest {
    private val parser = DirectMessageResponseParser()

    @Test
    fun normalizesInboxEntriesAndUsersNewestFirst() {
        val page = parser.parse(
            """
            {
              "inbox_initial_state": {
                "cursor": "99",
                "users": {
                  "42": {
                    "name": "Alice",
                    "screen_name": "alice",
                    "profile_image_url_https": "https://img.example/a.jpg"
                  }
                },
                "entries": [
                  {"message": {"id": "1", "time": "100", "conversation_id": "42-7",
                    "message_data": {"sender_id": "42", "text": "hello"}}},
                  {"message": {"id": "2", "time": "200", "conversation_id": "42-7",
                    "message_data": {"sender_id": "42", "text": "newest"}}}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("99", page.nextCursor)
        assertEquals(listOf("2", "1"), page.messages.map { it.id })
        assertEquals("42-7", page.messages.first().conversationId)
        assertEquals("42", page.messages.first().senderId)
        assertEquals("Alice", page.messages.first().senderName)
        assertEquals("alice", page.messages.first().senderUsername)
        assertEquals("https://img.example/a.jpg", page.messages.first().senderAvatarUrl)
        assertEquals("newest", page.messages.first().text)
        assertEquals(200L, page.messages.first().timestamp)
    }

    @Test
    fun usesRootEntriesAndCursorWhenInboxStateIsAbsent() {
        val page = parser.parse(
            """
            {
              "cursor": "root-cursor",
              "users": {"7": {"name": "Bob", "screen_name": "bob"}},
              "entries": [{"message": {"id": "7", "time": 7,
                "message_data": {"sender_id": "7", "text": "root message"}}}]
            }
            """.trimIndent(),
        )

        assertEquals("root-cursor", page.nextCursor)
        assertEquals(1, page.messages.size)
        assertEquals("root message", page.messages.single().text)
        assertEquals("bob", page.messages.single().senderUsername)
        assertEquals(7L, page.messages.single().timestamp)
    }

    @Test
    fun prefersInboxCursorAndKeepsMessageWhenUserIsMissing() {
        val page = parser.parse(
            """
            {
              "cursor": "root-cursor",
              "inbox_initial_state": {
                "cursor": "state-cursor",
                "users": [],
                "entries": [{"message": {"id": "missing-user", "conversation_id": null,
                  "message_data": {"sender_id": "404", "text": "still readable"}}}]
              }
            }
            """.trimIndent(),
        )

        val message = page.messages.single()
        assertEquals("state-cursor", page.nextCursor)
        assertEquals("404", message.senderId)
        assertNull(message.senderName)
        assertNull(message.senderUsername)
        assertNull(message.senderAvatarUrl)
        assertNull(message.conversationId)
        assertEquals(0L, message.timestamp)
    }

    @Test
    fun defaultsInvalidTimeAndSkipsEntriesWithMissingRequiredValues() {
        val page = parser.parse(
            """
            {
              "entries": [
                {"message": {"id": "no-sender", "message_data": {"text": "drop"}}},
                {"message": {"id": "no-text", "message_data": {"sender_id": "42"}}},
                {"message": {"message_data": {"sender_id": "42", "text": "drop"}}},
                {"message": {"id": "no-data", "time": "300"}},
                {"content": {"message": {"id": "nested", "message_data":
                  {"sender_id": "42", "text": "drop"}}}},
                {"message": {"id": "invalid-time", "time": "not-a-number",
                  "message_data": {"sender_id": "42", "text": "keep"}}}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("invalid-time"), page.messages.map { it.id })
        assertEquals(0L, page.messages.single().timestamp)
    }

    @Test
    fun acceptsMissingOptionalPayloadWithoutCreatingMessages() {
        val page = parser.parse("""{"data":{"viewer":null}}""")

        assertTrue(page.messages.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun convertsMalformedJsonToXApiException() {
        val exception = assertThrows(XApiException::class.java) {
            parser.parse("{not-json")
        }

        assertEquals("ダイレクトメッセージ応答を解析できません。", exception.message)
        assertNotNull(exception.cause)
    }
}
