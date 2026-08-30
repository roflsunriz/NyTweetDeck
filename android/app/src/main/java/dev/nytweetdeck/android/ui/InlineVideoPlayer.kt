package dev.nytweetdeck.android.ui

import androidx.annotation.OptIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.nytweetdeck.android.model.Media
import kotlin.math.max
import kotlin.math.min

private const val INLINE_PLAYBACK_VISIBLE_FRACTION = 0.6f

@OptIn(UnstableApi::class)
@Composable
internal fun InlineVideoPlayer(
    media: Media,
    loop: Boolean,
    modifier: Modifier = Modifier,
) {
    val uri = remember(media.url) { safeMediaUri(media.url) }
    val context = LocalContext.current
    val rootView = LocalView.current
    var visible by remember(media.id) { mutableStateOf(false) }
    val player = remember(media.id, uri) {
        uri?.let {
            CachedVideoPlayback.createPlayer(context).apply {
                volume = 0f
                repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(it))
                prepare()
            }
        }
    }

    LaunchedEffect(player, visible, loop) {
        player ?: return@LaunchedEffect
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playWhenReady = visible
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                this.player = player
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { it.player = player },
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
