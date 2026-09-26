package dev.nytweetdeck.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendFilterTest {
    private val trends = listOf(
        Trend("Japan", "technology news", "1", "https://x.com/i/trends/1", "Tech", null),
        Trend("Baseball", null, "2", "https://x.com/i/trends/2", "Sports", null),
    )

    @Test
    fun filtersByNameDescriptionAndDomain() {
        assertEquals(listOf("Japan"), filterTrends(trends, " technology ").map(Trend::name))
        assertEquals(listOf("Japan"), filterTrends(trends, "JAPAN").map(Trend::name))
        assertEquals(listOf("Baseball"), filterTrends(trends, "sports").map(Trend::name))
    }

    @Test
    fun returnsAllTrendsForBlankQuery() {
        assertEquals(trends, filterTrends(trends, ""))
        assertEquals(trends, filterTrends(trends, "   "))
    }
}
