package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.VideoQuality
import dev.nytweetdeck.android.model.VideoVariant
import dev.nytweetdeck.android.model.selectVideoUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineVideoPlayerTest {
    @Test
    fun calculatesClampedVisibleFractionForPlaybackThresholds() {
        assertEquals(1f, visibleFraction(100f, 300f, 0f, 500f), 0.001f)
        assertEquals(0.6f, visibleFraction(100f, 300f, 180f, 500f), 0.001f)
        assertEquals(0.5f, visibleFraction(100f, 300f, 200f, 500f), 0.001f)
        assertEquals(0f, visibleFraction(100f, 300f, 400f, 500f), 0.001f)
        assertEquals(0f, visibleFraction(100f, 100f, 0f, 500f), 0.001f)
        assertEquals(true, shouldAttachInlineVideo(0.6f))
        assertEquals(false, shouldAttachInlineVideo(0.599f))
    }

    @Test
    fun selectsTheConfiguredVideoQualityFromTheAvailableMp4Variants() {
        val media = Media(
            id = "video-1",
            type = "video",
            url = "https://video.example/high.mp4",
            previewUrl = "https://video.example/preview.jpg",
            variants = listOf(
                VideoVariant("https://video.example/low.mp4", 256_000),
                VideoVariant("https://video.example/mid.mp4", 832_000),
                VideoVariant("https://video.example/high.mp4", 2_000_000),
            ),
        )

        assertEquals("https://video.example/low.mp4", selectVideoUrl(media, VideoQuality.LOW))
        assertEquals("https://video.example/mid.mp4", selectVideoUrl(media, VideoQuality.MEDIUM))
        assertEquals("https://video.example/high.mp4", selectVideoUrl(media, VideoQuality.AUTO))
    }
}
