package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.*
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailMemoryTest {
    private fun page(id: String = "123"): PostDetailPage {
        val post = TimelineResponseParser().parse("""{"data":{"tweet":{"__typename":"Tweet","rest_id":"$id",
            "legacy":{"full_text":"cached"},"core":{"user_results":{"result":{"__typename":"User",
            "rest_id":"7","core":{"screen_name":"fixture","name":"Fixture"}}}}}}}""").posts.single()
        return PostDetailPage(post, emptyList(), "next", RankingMode.RELEVANCE)
    }
    private val key = DetailMemoryKey("7", "123", "ja", "RELEVANCE")

    @Test fun deduplicatesInFlightRequestsAndExpiresCachedPages() = runTest {
        var now = 0L
        var calls = 0
        val memory = PostDetailMemory(this, StandardTestDispatcher(testScheduler)) { now }
        val first = memory.request(key) { calls++; page() }
        val duplicate = memory.request(key) { error("duplicate load") }
        assertSame(first, duplicate)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("next", memory.cached(key)?.nextCursor)
        assertNull(memory.cached(key.copy(accountId = "8")))
        assertNull(memory.cached(key.copy(language = "en")))
        assertNull(memory.cached(key.copy(sort = "LIKES")))
        now = 120_000
        assertNull(memory.cached(key))
    }

    @Test fun boundedCacheAndSpeculationQuotaPreventUnboundedRequests() = runTest {
        val memory = PostDetailMemory(this, StandardTestDispatcher(testScheduler)) { 0L }
        var calls = 0
        repeat(10) { index ->
            memory.prefetch(key.copy(postId = "$index")) { calls++; page() }
            advanceUntilIdle()
        }
        assertEquals(6, calls)
        repeat(30) { index ->
            memory.request(key.copy(postId = "f$index")) { page() }
            advanceUntilIdle()
        }
        assertNull(memory.cached(key.copy(postId = "f0")))
        assertNotNull(memory.cached(key.copy(postId = "f29")))
    }

    @Test fun failuresNeverBecomeCacheAndRateLimitingPausesOnlySpeculation() = runTest {
        val memory = PostDetailMemory(this, StandardTestDispatcher(testScheduler)) { 0L }
        memory.prefetch(key) { throw XApiException("limited", 429) }
        advanceUntilIdle()
        assertNull(memory.cached(key))
        var calls = 0
        memory.prefetch(key.copy(postId = "124")) { calls++; page() }
        advanceUntilIdle()
        assertEquals(0, calls)
        val explicit = memory.request(key) { page() }
        advanceUntilIdle()
        assertEquals("123", explicit.await().post.id)
    }
}
