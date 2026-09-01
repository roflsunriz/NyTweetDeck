package dev.nytweetdeck.android.ui

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
}
