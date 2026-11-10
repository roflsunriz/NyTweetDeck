package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.VideoQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlaybackStateTest {
    @After
    fun clear() = VideoPlaybackStates.clear()

    @Test
    fun formatsUnknownMinuteAndHourDurations() {
        assertEquals("00:00", formatVideoTime(-1L))
        assertEquals("01:05", formatVideoTime(65_999L))
        assertEquals("59:59", formatVideoTime(3_599_999L))
        assertEquals("01:00:00", formatVideoTime(3_600_000L))
        assertEquals("12:34:56", formatVideoTime(45_296_000L))
    }

    @Test
    fun retainsAllUserPlaybackChoicesAcrossViewsWithoutSharingDifferentMedia() {
        val state = VideoPlaybackSnapshot(
            position = 12_345L, playWhenReady = false, muted = false, volume = 0.42f,
            quality = VideoQuality.LOW, loop = false,
        )
        VideoPlaybackStates.save("same-media", state)
        assertEquals(state, VideoPlaybackStates.get("same-media"))
        assertNull(VideoPlaybackStates.get("other-media"))
    }

    @Test
    fun keepsOnlyTheMostRecentlyUsedSixtyFourMediaStates() {
        val state = VideoPlaybackSnapshot(
            playWhenReady = true, volume = 0.5f, quality = VideoQuality.AUTO, loop = true,
        )
        repeat(64) { VideoPlaybackStates.save("$it", state) }
        VideoPlaybackStates.save("0", state.copy(position = 100L))
        VideoPlaybackStates.save("64", state)
        assertEquals(100L, VideoPlaybackStates.get("0")?.position)
        assertNull(VideoPlaybackStates.get("1"))
    }
}
