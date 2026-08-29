package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TrendResponseParserTest {
    private val parser = TrendResponseParser()

    @Test
    fun parsesExploreTrendFieldsAndBottomCursor() {
        val page = parser.parse(
            """
            {
              "data": {
                "explore_page": {
                  "body": {
                    "initialTimeline": {
                      "timeline": {
                        "timeline": {
                          "instructions": [
                            {
                              "entries": [
                                {
                                  "content": {
                                    "entryType": "TimelineTimelineItem",
                                    "itemContent": {
                                      "__typename": "TimelineTrend",
                                      "itemType": "TimelineTrend",
                                      "name": "#NyTweetDeck",
                                      "description": "1,234 posts",
                                      "rank": "1",
                                      "trend_metadata": {"domain_context": "Technology"},
                                      "trend_url": {"url": "twitter://search?query=NyTweetDeck"}
                                    }
                                  }
                                },
                                {
                                  "content": {
                                    "entryType": "TimelineTimelineCursor",
                                    "cursorType": "Bottom",
                                    "value": "next-current"
                                  }
                                }
                              ]
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("next-current", page.nextCursor)
        assertEquals(1, page.trends.size)
        val trend = page.trends.single()
        assertEquals("#NyTweetDeck", trend.name)
        assertEquals("1", trend.rank)
        assertEquals("1,234 posts", trend.description)
        assertEquals("Technology", trend.domainContext)
        assertNull(trend.metaDescription)
        assertEquals("https://x.com/search?q=%23NyTweetDeck", trend.url)
    }

    @Test
    fun preservesOfficialHttpsUrlsAndFallsBackForUnsafeUrls() {
        val page = parser.parse(
            """
            {"trends":[
              {"name":"X", "trend_url":{"url":"https://x.com/search?q=X"}, "trend_metadata":{}},
              {"name":"Twitter", "trend_url":{"url":"https://mobile.twitter.com/search?q=Twitter"}, "trend_metadata":{}},
              {"name":"Http", "trend_url":{"url":"http://x.com/search?q=Http"}, "trend_metadata":{}},
              {"name":"External", "trend_url":{"url":"https://example.com/search?q=External"}, "trend_metadata":{}},
              {"name":"Credentials", "trend_url":{"url":"https://user@x.com/search?q=Credentials"}, "trend_metadata":{}}
            ]}
            """.trimIndent(),
        )

        assertEquals("https://x.com/search?q=X", page.trends[0].url)
        assertEquals("https://mobile.twitter.com/search?q=Twitter", page.trends[1].url)
        assertEquals("https://x.com/search?q=Http", page.trends[2].url)
        assertEquals("https://x.com/search?q=External", page.trends[3].url)
        assertEquals("https://x.com/search?q=Credentials", page.trends[4].url)
    }

    @Test
    fun encodesFallbackQueryAndDeduplicatesByFirstName() {
        val page = parser.parse(
            """
            {"trends":[
              {"name":"Needs Encoding + 日本", "description":"first", "rank":"1",
               "trend_url":{"url":"twitter://search"}, "trend_metadata":{"domain_context":"News"}},
              {"name":"Needs Encoding + 日本", "description":"duplicate", "rank":"9",
               "trend_url":{"url":"https://example.com"}, "trend_metadata":{"domain_context":"Other"}},
              {"name":"Other", "trend_url":{"url":"https://x.com/search?q=Other"}, "trend_metadata":{}}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("Needs Encoding + 日本", "Other"), page.trends.map { it.name })
        assertEquals("first", page.trends[0].description)
        assertEquals("1", page.trends[0].rank)
        assertEquals("News", page.trends[0].domainContext)
        assertEquals(
            "https://x.com/search?q=Needs%20Encoding%20%2B%20%E6%97%A5%E6%9C%AC",
            page.trends[0].url,
        )
    }

    @Test
    fun readsSnakeCaseAndEntryIdBottomCursors() {
        val page = parser.parse(
            """
            {"items":[
              {"name":"First", "trend_url":{}, "trend_metadata":{},
               "cursor_type":"Top", "value":"ignored"},
              {"entry_id":"cursor-bottom-1", "content":{"value":"next-snake"}}
            ]}
            """.trimIndent(),
        )

        assertEquals("next-snake", page.nextCursor)
        assertEquals(listOf("First"), page.trends.map { it.name })
    }

    @Test
    fun convertsMalformedJsonToXApiException() {
        val exception = assertThrows(XApiException::class.java) {
            parser.parse("{not-json")
        }

        assertEquals("トレンド応答を解析できません。", exception.message)
        assertNotNull(exception.cause)
    }

    @Test
    fun returnsEmptyPageForPayloadWithoutTrends() {
        val page = parser.parse("{\"data\":{\"viewer\":null}}")

        assertEquals(emptyList<Any>(), page.trends)
        assertNull(page.nextCursor)
    }
}
