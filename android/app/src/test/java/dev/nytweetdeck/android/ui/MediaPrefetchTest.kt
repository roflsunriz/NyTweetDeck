package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.*
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import org.junit.Assert.*
import org.junit.Test

class MediaPrefetchTest {
    private fun post() = TimelineResponseParser().parse("""{"data":{"tweet":{"__typename":"Tweet","rest_id":"123",
        "legacy":{"full_text":"cached"},"core":{"user_results":{"result":{"__typename":"User",
        "rest_id":"7","core":{"screen_name":"fixture","name":"Fixture"}}}}}}}""").posts.single()

    @Test fun includesPhotosPostersAndChosenVideoQualityButRejectsUnsafeOrigins() {
        val media = listOf(Media("p", "photo", "https://pbs.twimg.com/p.jpg", null),
            Media("v", "video", "https://video.twimg.com/v.mp4", "https://pbs.twimg.com/v.jpg", listOf(
                VideoVariant("https://video.twimg.com/low.mp4", 1),
                VideoVariant("https://video.twimg.com/high.mp4", 2))),
            Media("unsafe", "photo", "https://evil.example/steal", null))
        val requests = mediaWarmRequests(listOf(post().copy(media = media)), VideoQuality.LOW)
        assertEquals(listOf("https://pbs.twimg.com/p.jpg", "https://pbs.twimg.com/v.jpg", "https://video.twimg.com/low.mp4"),
            requests.map { it.url })
        assertEquals(listOf(false, false, true), requests.map { it.video })
    }

    @Test fun deduplicatesAndBoundsWarmingToEightUrls() {
        val media = (1..20).map { Media("$it", "photo", "https://pbs.twimg.com/$it.jpg", null) }
        val requests = mediaWarmRequests(listOf(post().copy(media = media), post().copy(media = media)), VideoQuality.AUTO)
        assertEquals(8, requests.size)
        assertEquals(requests.size, requests.distinctBy { it.url }.size)
    }
}
