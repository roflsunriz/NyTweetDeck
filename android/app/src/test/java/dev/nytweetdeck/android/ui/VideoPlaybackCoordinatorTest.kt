package dev.nytweetdeck.android.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlaybackCoordinatorTest {
    @After
    fun tearDown() {
        VideoPlaybackCoordinator.resetForTesting()
    }

    @Test
    fun keepsOnlyTheLatestPlayback() {
        VideoPlaybackCoordinator.claim("a")
        assertEquals("a", VideoPlaybackCoordinator.current())
        VideoPlaybackCoordinator.claim("b")
        assertEquals("b", VideoPlaybackCoordinator.current())
    }

    @Test
    fun notifiesObserversOnSwitch() {
        val seen = mutableListOf<String?>()
        val subscription = VideoPlaybackCoordinator.observe(seen::add)
        VideoPlaybackCoordinator.claim("a")
        VideoPlaybackCoordinator.claim("b")
        subscription.close()
        VideoPlaybackCoordinator.claim("c")
        assertEquals(listOf("a", "b"), seen)
        assertEquals("c", VideoPlaybackCoordinator.current())
    }

    @Test
    fun ignoresReleaseFromNonHolder() {
        VideoPlaybackCoordinator.claim("a")
        VideoPlaybackCoordinator.release("b")
        assertEquals("a", VideoPlaybackCoordinator.current())
        VideoPlaybackCoordinator.release("a")
        assertNull(VideoPlaybackCoordinator.current())
    }
}
