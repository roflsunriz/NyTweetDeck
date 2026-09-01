package dev.nytweetdeck.android.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Media
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

private const val INLINE_PLAYBACK_VISIBLE_FRACTION = 0.6f

@OptIn(UnstableApi::class)
@Composable
internal fun InlineVideoPlayer(
    media: Media,
    autoPlay: Boolean,
    loop: Boolean,
    volume: Int,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = remember(media.url) { safeMediaUri(media.url) }
    val context = LocalContext.current
    val rootView = LocalView.current
    var visible by remember(media.id) { mutableStateOf(false) }
    var player by remember(media.id) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(media.id) { mutableStateOf(false) }
    var muted by remember(media.id) { mutableStateOf(true) }
    var loopActive by remember(media.id, loop) { mutableStateOf(loop) }
    var playbackVolume by remember(media.id, volume) {
        mutableFloatStateOf(volume.coerceIn(0, 100) / 100f)
    }
    var position by remember(media.id) { mutableLongStateOf(0L) }
    var duration by remember(media.id) { mutableLongStateOf(0L) }

    LaunchedEffect(uri, visible) {
        if (visible && uri != null && player == null) {
            player = CachedVideoPlayback.createPlayer(context).apply {
                this.volume = 0f
                repeatMode = if (loopActive) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = autoPlay
            }
        } else if (!visible && player != null) {
            player?.run {
                stop()
                clearMediaItems()
                release()
            }
            player = null
            playing = false
            position = 0L
            duration = 0L
            muted = true
            loopActive = loop
        }
    }
    LaunchedEffect(player, autoPlay) { player?.playWhenReady = autoPlay }
    LaunchedEffect(player, loopActive, muted, playbackVolume) {
        player?.let {
            it.repeatMode = if (loopActive) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            it.volume = if (muted) 0f else playbackVolume
        }
    }
    LaunchedEffect(player, playing) {
        while (player != null) {
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = player?.duration?.takeIf { it > 0L } ?: 0L
            delay(if (playing) 250L else 750L)
        }
    }
    DisposableEffect(player) {
        val activePlayer = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                playing = value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = activePlayer?.duration?.takeIf { it > 0L } ?: 0L
            }
        }
        activePlayer?.addListener(listener)
        onDispose { activePlayer?.removeListener(listener) }
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                visible = shouldAttachInlineVideo(
                    visibleFraction(bounds.top, bounds.bottom, 0f, rootView.height.toFloat()),
                )
            }
            .testTag("inline-video-" + media.id),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.player = player
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { it.player = player },
            modifier = Modifier
                .fillMaxSize()
                .testTag(if (player == null) "inline-video-disconnected" else "inline-video-connected"),
        )
        if (player != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Slider(
                    value = if (duration > 0L) position.coerceAtMost(duration).toFloat() else 0f,
                    onValueChange = { player?.seekTo(it.toLong()) },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.fillMaxWidth().testTag("inline-video-seek"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                        modifier = Modifier.size(34.dp).testTag("inline-video-play"),
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            stringResource(if (playing) R.string.media_viewer_pause else R.string.media_viewer_play),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = { muted = !muted },
                        modifier = Modifier.size(34.dp).testTag("inline-video-mute"),
                    ) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff
                            else Icons.AutoMirrored.Filled.VolumeUp,
                            stringResource(if (muted) R.string.media_viewer_unmute else R.string.media_viewer_mute),
                            tint = Color.White,
                        )
                    }
                    Slider(
                        value = playbackVolume,
                        onValueChange = {
                            playbackVolume = it.coerceIn(0f, 1f)
                            if (playbackVolume > 0f) muted = false
                        },
                        modifier = Modifier.weight(1f).testTag("inline-video-volume"),
                    )
                    IconButton(
                        onClick = { loopActive = !loopActive },
                        modifier = Modifier
                            .size(34.dp)
                            .semantics { selected = loopActive }
                            .testTag("inline-video-loop"),
                    ) {
                        Icon(Icons.Default.Repeat, stringResource(R.string.setting_video_loop), tint = Color.White)
                    }
                    IconButton(
                        onClick = onFullscreen,
                        modifier = Modifier.size(34.dp).testTag("inline-video-fullscreen"),
                    ) {
                        Icon(Icons.Default.Fullscreen, stringResource(R.string.inline_video_fullscreen), tint = Color.White)
                    }
                }
            }
        }
    }
}

internal fun shouldAttachInlineVideo(visibleFraction: Float): Boolean =
    visibleFraction >= INLINE_PLAYBACK_VISIBLE_FRACTION

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
