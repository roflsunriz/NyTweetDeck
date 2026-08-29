package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TimelineQueryFactoryTest {
    @Test
    fun followingTimelineUsesUnrankedWebVariablesAndCursor() {
        val query = TimelineQueryFactory.create("homeFollowing", null, "cursor-1")

        assertEquals("homeFollowing", query.purpose)
        assertEquals(false, query.variables["enableRanking"])
        assertEquals("cursor-1", query.variables["cursor"])
    }

    @Test
    fun searchUsesLatestProductAndRequiresTarget() {
        val query = TimelineQueryFactory.create("search", "NyTweetDeck", null)

        assertEquals("Latest", query.variables["product"])
        assertEquals("NyTweetDeck", query.variables["rawQuery"])
        assertFalse(query.variables.containsKey("cursor"))
        assertThrows(IllegalArgumentException::class.java) {
            TimelineQueryFactory.create("search", " ", null)
        }
    }

    @Test
    fun unsupportedKindIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TimelineQueryFactory.create("unknown", null, null)
        }
    }
}
