package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ListDirectoryParserTest {
    private val parser = ListDirectoryParser()

    @Test
    fun parsesCurrentWebListsWithOwnerCountsAndBottomCursor() {
        val page = parser.parse(
            """
            {"data":{"user":{"result":{"timeline":{"timeline":{"instructions":[{"entries":[
              {"content":{"itemContent":{"__typename":"TimelineTwitterList","itemType":"TimelineTwitterList",
                "list":{"id":"84","id_str":"84","name":"Friends","description":"People I know",
                  "member_count":5,"subscriber_count":2,"user_results":{"result":{"__typename":"User",
                    "core":{"name":"Alice","screen_name":"alice"}}}}}}},
              {"entryId":"cursor-bottom","content":{"cursorType":"Bottom","value":"next"}}
            ]}]}}}}}}
            """.trimIndent(),
            "mine",
        )

        assertEquals("next", page.nextCursor)
        val list = page.lists.single()
        assertEquals("84", list.id)
        assertEquals("Friends", list.name)
        assertEquals("People I know", list.description)
        assertEquals("Alice", list.ownerName)
        assertEquals("alice", list.ownerUsername)
        assertEquals(5L, list.memberCount)
        assertEquals(2L, list.subscriberCount)
        assertEquals("mine", list.source)
    }

    @Test
    fun preservesSuggestedAndSearchSources() {
        val body = """
            {"items":[{"__typename":"TimelineTwitterList","list":{
              "id_str":"91","name":"Suggestions","member_count":"9","subscriber_count":"7"}}]}
        """.trimIndent()

        assertEquals("suggested", parser.parse(body, "suggested").lists.single().source)
        assertEquals("search", parser.parse(body, "search").lists.single().source)
    }

    @Test
    fun deduplicatesByFirstListIdInResponseOrder() {
        val page = parser.parse(
            """
            {"entries":[
              {"__typename":"TimelineTwitterList","list":{"id_str":"1","name":"First",
                "description":"first description","member_count":1,"subscriber_count":2}},
              {"__typename":"TimelineTwitterList","list":{"id_str":"2","name":"Second",
                "member_count":3,"subscriber_count":4}},
              {"__typename":"TimelineTwitterList","list":{"id_str":"1","name":"Replacement",
                "member_count":99,"subscriber_count":99}}
            ]}
            """.trimIndent(),
            "search",
        )

        assertEquals(listOf("1", "2"), page.lists.map { it.id })
        assertEquals("First", page.lists.first().name)
        assertEquals("first description", page.lists.first().description)
    }

    @Test
    fun ignoresIncompleteAndUnrelatedObjectsAndDefaultsOptionalValues() {
        val page = parser.parse(
            """
            {"items":[
              {"__typename":"TimelineTwitterList","list":{"id":"only-id","name":"No id str"}},
              {"__typename":"TimelineTwitterList","list":{"id_str":"no-name"}},
              {"__typename":"Other","list":{"id_str":"other","name":"Other"}},
              {"__typename":"TimelineTwitterList","list":{"id_str":"valid","name":"Valid",
                "description":null,"member_count":"invalid","subscriber_count":null,
                "user_results":{"result":{"core":{}}}}}
            ]}
            """.trimIndent(),
            "suggested",
        )

        val list = page.lists.single()
        assertEquals("valid", list.id)
        assertNull(list.description)
        assertNull(list.ownerName)
        assertNull(list.ownerUsername)
        assertEquals(0L, list.memberCount)
        assertEquals(0L, list.subscriberCount)
    }

    @Test
    fun usesLastBottomCursorAndIgnoresOtherCursorTypes() {
        val page = parser.parse(
            """
            {"items":[
              {"cursorType":"Top","value":"ignored"},
              {"cursorType":"Bottom","value":"first-bottom"},
              {"nested":{"cursorType":"bottom","value":"last-bottom"}}
            ]}
            """.trimIndent(),
            "mine",
        )

        assertEquals("last-bottom", page.nextCursor)
        assertTrue(page.lists.isEmpty())
    }

    @Test
    fun returnsEmptyPageForPayloadWithoutLists() {
        val page = parser.parse("{\"data\":{\"viewer\":null}}", "mine")

        assertTrue(page.lists.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun convertsMalformedJsonToXApiException() {
        val exception = assertThrows(XApiException::class.java) {
            parser.parse("{not-json", "mine")
        }

        assertEquals("Xリスト一覧を解析できません。", exception.message)
        assertNotNull(exception.cause)
    }
}
