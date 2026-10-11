package dev.nytweetdeck.android.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.nytweetdeck.android.MainActivity
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 実際のMedia3再生状態を使うが、X通信と動画デコーダには依存しない。 */
@OptIn(UnstableApi::class)
class VideoPlaybackSurfaceUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun inlinePlaybackKeepsTheScreenOnlyWhilePlayingAndReleasesOffscreen() {
        val suspended = mutableStateOf(false)
        val offscreen = mutableStateOf(false)
        val displayed = mutableStateOf(true)
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                if (displayed.value) {
                    Box(Modifier.fillMaxWidth().height(220.dp).offset(y = if (offscreen.value) 2_000.dp else 0.dp)) {
                        InlineVideoPlayer(
                            media = media("inline"), autoPlay = false, loop = false, volume = 0,
                            suspended = suspended.value, onFullscreen = {},
                        )
                    }
                }
            }
        }
        val view = awaitPlayerViews(1).single()
        assertScreenHeld(view, false)
        startSilentPlayback(view)
        assertScreenHeld(view, true)
        composeRule.runOnIdle { view.player!!.pause() }
        assertScreenHeld(view, false)
        composeRule.runOnIdle { view.player!!.play() }
        assertScreenHeld(view, true)
        composeRule.runOnIdle { view.player!!.seekTo(60_000L) }
        waitForPlayer(view) { it.playbackState == Player.STATE_ENDED }
        assertScreenHeld(view, false)
        startSilentPlayback(view)
        assertScreenHeld(view, true)
        composeRule.runOnIdle { suspended.value = true }
        assertScreenHeld(view, false)
        composeRule.waitUntil(5_000) { playerViews().isEmpty() }
        composeRule.runOnIdle { suspended.value = false }
        val restoredView = awaitPlayerViews(1).single()
        assertScreenHeld(restoredView, false)
        startSilentPlayback(restoredView)
        assertScreenHeld(restoredView, true)
        composeRule.runOnIdle { offscreen.value = true }
        assertScreenHeld(restoredView, false)
        composeRule.waitUntil(5_000) { playerViews().isEmpty() }
        composeRule.runOnIdle { offscreen.value = false }
        val visibleView = awaitPlayerViews(1).single()
        startSilentPlayback(visibleView)
        assertScreenHeld(visibleView, true)
        composeRule.runOnIdle { displayed.value = false }
        assertScreenHeld(visibleView, false)
    }

    @Test
    fun anotherInlinePlayerCanReleaseWithoutClearingTheCurrentPlayersScreenRequest() {
        val firstDisplayed = mutableStateOf(true)
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                Column {
                    if (firstDisplayed.value) {
                        Box(Modifier.fillMaxWidth().height(180.dp)) {
                            InlineVideoPlayer(
                                media = media("first"), autoPlay = false, loop = false,
                                volume = 0, onFullscreen = {},
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(180.dp)) {
                        InlineVideoPlayer(
                            media = media("second"), autoPlay = false, loop = false,
                            volume = 0, onFullscreen = {},
                        )
                    }
                }
            }
        }
        val (firstView, secondView) = awaitPlayerViews(2)
        startSilentPlayback(firstView)
        assertScreenHeld(firstView, true)
        startSilentPlayback(secondView)
        assertScreenHeld(secondView, true)
        assertScreenHeld(firstView, false)
        composeRule.runOnIdle { firstDisplayed.value = false }
        assertScreenHeld(firstView, false)
        assertScreenHeld(secondView, true)
        composeRule.runOnIdle { secondView.player!!.pause() }
        assertScreenHeld(secondView, false)
    }

    @Test
    fun fullscreenPlaybackReleasesTheScreenOnPauseAndClose() {
        val displayed = mutableStateOf(true)
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                if (displayed.value) {
                    FullscreenVideoPlayer(
                        media = media("fullscreen"), autoPlay = false, loop = false,
                        volume = 0, onExitFullscreen = {}, onRotateToLandscape = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val view = awaitPlayerViews(1).single()
        assertScreenHeld(view, false)
        startSilentPlayback(view)
        assertScreenHeld(view, true)
        composeRule.runOnIdle { view.player!!.pause() }
        assertScreenHeld(view, false)
        composeRule.runOnIdle { view.player!!.play() }
        assertScreenHeld(view, true)
        composeRule.runOnIdle { displayed.value = false }
        assertScreenHeld(view, false)
    }

    @Test
    fun portraitVideoBarsCoverThePreviewAndDisconnectedVideoRestoresIt() {
        val suspended = mutableStateOf(false)
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                // 赤色は動画の下にあるCropプレビューの代わり。透明な余白なら赤くなる。
                Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Red)) {
                    InlineVideoPlayer(
                        media = media("portrait"), autoPlay = false, loop = false, volume = 0,
                        suspended = suspended.value, onFullscreen = {},
                    )
                }
            }
        }
        val view = awaitPlayerViews(1).single()
        composeRule.runOnIdle {
            view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                .setAspectRatio(9f / 16f)
        }
        assertBars("inline-video-portrait", Color.Black)
        composeRule.runOnIdle { suspended.value = true }
        composeRule.waitUntil(5_000) { playerViews().isEmpty() }
        assertBars("inline-video-portrait", Color.Red)
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                Box(Modifier.fillMaxSize().background(Color.Red)) {
                    FullscreenVideoPlayer(
                        media = media("portrait-fullscreen"), autoPlay = false, loop = false,
                        volume = 0, onExitFullscreen = {}, onRotateToLandscape = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val fullscreenView = awaitPlayerViews(1).single()
        composeRule.runOnIdle {
            // 縦長画面でも左右に余白が出る、画面より細い動画を再現する。
            fullscreenView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                .setAspectRatio(fullscreenView.width.toFloat() / fullscreenView.height / 2f)
        }
        assertBars("media-video-portrait-fullscreen", Color.Black)
    }

    private fun startSilentPlayback(view: PlayerView) {
        composeRule.runOnIdle {
            (view.player as ExoPlayer).apply {
                setMediaSource(SilenceMediaSource.Factory().setDurationUs(60_000_000L).createMediaSource())
                volume = 0f
                prepare()
                play()
            }
        }
        waitForPlayer(view) { it.isPlaying }
    }

    private fun assertScreenHeld(view: PlayerView, expected: Boolean) {
        composeRule.waitUntil(5_000) {
            var held = !expected
            composeRule.runOnUiThread { held = view.keepScreenOn }
            held == expected
        }
        composeRule.runOnIdle {
            if (expected) assertTrue(view.keepScreenOn) else assertFalse(view.keepScreenOn)
        }
    }

    private fun waitForPlayer(view: PlayerView, condition: (Player) -> Boolean) {
        composeRule.waitUntil(5_000) {
            var reached = false
            composeRule.runOnUiThread { reached = view.player?.let(condition) == true }
            reached
        }
    }

    private fun assertBars(tag: String, color: Color) {
        composeRule.waitForIdle()
        val pixels = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .captureToImage().toPixelMap()
        assertEquals(color, pixels[2, pixels.height / 2])
        assertEquals(color, pixels[pixels.width - 3, pixels.height / 2])
    }

    private fun awaitPlayerViews(count: Int): List<PlayerView> {
        composeRule.waitUntil(5_000) { playerViews().size == count }
        return playerViews()
    }

    private fun playerViews(): List<PlayerView> {
        var result = emptyList<PlayerView>()
        composeRule.runOnUiThread {
            result = descendants(composeRule.activity.window.decorView).filterIsInstance<PlayerView>()
        }
        return result
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
        }
    }

    private fun media(id: String) = Media(
        id = id, type = "video", url = "https://video.twimg.com/ext_tw_video/fixture.mp4",
        previewUrl = null,
    )
}
