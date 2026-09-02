package dev.nytweetdeck.android.ui

import androidx.compose.ui.geometry.Offset
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.VideoQuality
import dev.nytweetdeck.android.model.VideoVariant
import dev.nytweetdeck.android.model.selectVideoUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineVideoPlayerTest {
    @Test
    fun pinchZoomsVideoInAndOutWithinTheSupportedRange() {
        val centroid = Offset(100f, 100f)
        val pan = Offset(24f, -12f)
        val zoomed = updateVideoViewportTransform(
            scale = 1f,
            offset = Offset.Zero,
            centroid = centroid,
            viewportCenter = Offset(200f, 150f),
            zoomChange = 2f,
            panChange = pan,
        )
        assertEquals(2f, zoomed.scale, 0.001f)
        assertEquals(centroid + pan, centroid * zoomed.scale + zoomed.offset)

        val zoomedOut = updateVideoViewportTransform(
            scale = zoomed.scale,
            offset = zoomed.offset,
            centroid = centroid,
            viewportCenter = Offset(200f, 150f),
            zoomChange = 0.25f,
            panChange = Offset(10f, 10f),
        )
        assertEquals(1f, zoomedOut.scale, 0.001f)
        assertEquals(Offset.Zero, zoomedOut.offset)

        val clampedMaximum = updateVideoViewportTransform(
            scale = 2f,
            offset = Offset.Zero,
            centroid = Offset.Unspecified,
            viewportCenter = Offset(200f, 150f),
            zoomChange = 10f,
            panChange = Offset.Zero,
        )
        assertEquals(4f, clampedMaximum.scale, 0.001f)
    }

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
