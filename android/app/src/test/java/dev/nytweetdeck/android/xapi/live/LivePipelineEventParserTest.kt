package dev.nytweetdeck.android.xapi.live

import dev.nytweetdeck.android.xapi.XApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LivePipelineEventParserTest {
    private val parser = LivePipelineEventParser()

    @Test
    fun parsesOfficialSystemConfigAndDefaultsInvalidTtl() {
        val event = parser.parse(
            """{"topic":"/system/config","payload":{"config":{"session_id":"session-1","subscription_ttl_millis":0}}}""",
        ) as ParsedLivePipelineEvent.SystemConfig

        assertEquals("session-1", event.config.sessionId)
        assertEquals(120_000L, event.config.subscriptionTtlMilliseconds)
    }

    @Test
    fun parsesEngagementAndDirectMessageEventsIntoTypedPayloads() {
        val engagement = parser.parse(
            """{"topic":"/tweet_engagement/123","payload":{"tweet_engagement":{"favorite_count":"8","retweet_count":3}}}""",
        ) as ParsedLivePipelineEvent.TweetEngagement
        val update = parser.parse(
            """{"topic":"/dm_update/42","payload":{"dm_update":{"event_id":"e1"}}}""",
        ) as ParsedLivePipelineEvent.DirectMessageUpdate
        val typing = parser.parse(
            """{"topic":"/dm_typing/42","payload":{"dm_typing":{"typing":true}}}""",
        ) as ParsedLivePipelineEvent.DirectMessageTyping

        assertEquals("123", engagement.postId)
        assertEquals(8L, engagement.counts.likeCount)
        assertEquals(3L, engagement.counts.repostCount)
        assertEquals("42", update.userId)
        assertEquals("e1", update.payload.toString().substringAfter("event_id\":\"").substringBefore('"'))
        assertEquals("42", typing.userId)
    }

    @Test
    fun rejectsUnknownOrMalformedEvents() {
        assertThrows(XApiException::class.java) {
            parser.parse("""{"topic":"/future/1","payload":{"future":{"value":"1"}}}""")
        }
        assertThrows(XApiException::class.java) {
            parser.parse("""{"topic":"/system/config","payload":{"config":{"session_id":""}}}""")
        }
    }
}
