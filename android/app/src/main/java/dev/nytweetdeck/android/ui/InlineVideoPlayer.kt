package dev.nytweetdeck.android.ui

import android.graphics.Color
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import dev.nytweetdeck.android.model.Media
import kotlin.math.max
import kotlin.math.min

private const val INLINE_PLAYBACK_VISIBLE_FRACTION = 0.6f

@Composable
internal fun InlineVideoPlayer(
    media: Media,
    loop: Boolean,
    modifier: Modifier = Modifier,
) {
    val uri = remember(media.url) { safeMediaUri(media.url) }
    val rootView = LocalView.current
    var visible by remember(media.id) { mutableStateOf(false) }
    var videoView by remember(media.id) { mutableStateOf<VideoView?>(null) }

    LaunchedEffect(videoView, uri, visible, loop) {
        val playerView = videoView ?: return@LaunchedEffect
        playerView.setOnPreparedListener(null)
        playerView.setOnErrorListener(null)
        if (!visible || uri == null) {
            playerView.stopPlayback()
            return@LaunchedEffect
        }
        playerView.setVideoURI(uri)
        playerView.setOnPreparedListener { player ->
            player.isLooping = loop
            player.setVolume(0f, 0f)
            if (visible) playerView.start()
        }
        playerView.setOnErrorListener { _, _, _ ->
            playerView.stopPlayback()
            true
        }
    }
    DisposableEffect(videoView) {
        onDispose {
            videoView?.setOnPreparedListener(null)
            videoView?.setOnErrorListener(null)
            videoView?.stopPlayback()
        }
    }
    AndroidView(
        factory = { context ->
            VideoView(context).also { view ->
                view.setBackgroundColor(Color.TRANSPARENT)
                view.isClickable = false
                view.isFocusable = false
                videoView = view
            }
        },
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                visible = visibleFraction(bounds.top, bounds.bottom, 0f, rootView.height.toFloat()) >=
                    INLINE_PLAYBACK_VISIBLE_FRACTION
            }
            .testTag("inline-video-" + media.id),
    )
}

internal fun visibleFraction(
    itemTop: Float,
    itemBottom: Float,
    viewportTop: Float,
    viewportBottom: Float,
): Float {
    val height = itemBottom - itemTop
    if (height <= 0f || viewportBottom <= viewportTop) return 0f
    val visibleHeight = min(itemBottom, viewportBottom) - max(itemTop, viewportTop)
    return (visibleHeight.coerceAtLeast(0f) / height).coerceIn(0f, 1f)
}
