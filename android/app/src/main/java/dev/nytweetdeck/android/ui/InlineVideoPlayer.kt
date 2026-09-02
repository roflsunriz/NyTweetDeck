package dev.nytweetdeck.android.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.VideoQuality
import dev.nytweetdeck.android.model.selectVideoUrl
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

private const val INLINE_PLAYBACK_VISIBLE_FRACTION = 0.6f
private const val VIDEO_MIN_SCALE = 1f
private const val VIDEO_MAX_SCALE = 4f
internal const val VIDEO_CONTROLS_HIDE_DELAY_MILLIS = 3_000L

internal data class VideoViewportTransform(
    val scale: Float,
    val offset: Offset,
)

internal fun updateVideoViewportTransform(
    scale: Float,
    offset: Offset,
    centroid: Offset,
    viewportCenter: Offset,
    zoomChange: Float,
    panChange: Offset,
): VideoViewportTransform {
    val nextScale = (scale * zoomChange).coerceIn(VIDEO_MIN_SCALE, VIDEO_MAX_SCALE)
    val effectiveCentroid = centroid.takeIf { it.isSpecified } ?: viewportCenter
    val scaleRatio = nextScale / scale
    return VideoViewportTransform(
        scale = nextScale,
        offset = if (nextScale <= VIDEO_MIN_SCALE) {
            Offset.Zero
        } else {
            effectiveCentroid - (effectiveCentroid - offset) * scaleRatio + panChange
        },
    )
}

@Composable
internal fun InlineVideoPlayer(
    media: Media,
    autoPlay: Boolean,
    loop: Boolean,
    volume: Int,
    defaultQuality: VideoQuality = VideoQuality.AUTO,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoPlayer(
        media = media,
        autoPlay = autoPlay,
        loop = loop,
        volume = volume,
        defaultQuality = defaultQuality,
        onFullscreen = onFullscreen,
        modifier = modifier,
        attachOnlyWhenVisible = true,
        fullscreen = false,
        controlTagPrefix = "inline-video",
    )
}

@Composable
internal fun FullscreenVideoPlayer(
    media: Media,
    autoPlay: Boolean,
    loop: Boolean,
    volume: Int,
    defaultQuality: VideoQuality = VideoQuality.AUTO,
    onExitFullscreen: () -> Unit,
    onRotateToLandscape: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoPlayer(
        media = media,
        autoPlay = autoPlay,
        loop = loop,
        volume = volume,
        defaultQuality = defaultQuality,
        onFullscreen = onExitFullscreen,
        onRotateToLandscape = onRotateToLandscape,
        modifier = modifier,
        attachOnlyWhenVisible = false,
        fullscreen = true,
        controlTagPrefix = "media-video",
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    media: Media,
    autoPlay: Boolean,
    loop: Boolean,
    volume: Int,
    defaultQuality: VideoQuality,
    onFullscreen: () -> Unit,
    modifier: Modifier,
    attachOnlyWhenVisible: Boolean,
    fullscreen: Boolean,
    controlTagPrefix: String,
    onRotateToLandscape: (() -> Unit)? = null,
) {
    var selectedQuality by remember(media.id, defaultQuality) { mutableStateOf(defaultQuality) }
    LaunchedEffect(defaultQuality) { selectedQuality = defaultQuality }
    val uri = remember(media.id, media.url, media.variants, selectedQuality) {
        safeMediaUri(selectVideoUrl(media, selectedQuality))
    }
    val context = LocalContext.current
    val rootView = LocalView.current
    var visible by remember(media.id, attachOnlyWhenVisible) {
        mutableStateOf(!attachOnlyWhenVisible)
    }
    var player by remember(media.id) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(media.id) { mutableStateOf(false) }
    var muted by remember(media.id) { mutableStateOf(true) }
    var loopActive by remember(media.id, loop) { mutableStateOf(loop) }
    var playbackVolume by remember(media.id, volume) {
        mutableFloatStateOf(volume.coerceIn(0, 100) / 100f)
    }
    var position by remember(media.id) { mutableLongStateOf(0L) }
    var duration by remember(media.id) { mutableLongStateOf(0L) }
    var controlsVisible by remember(media.id) { mutableStateOf(true) }
    var controlsInteraction by remember(media.id) { mutableIntStateOf(0) }
    var qualityMenuExpanded by remember(media.id) { mutableStateOf(false) }
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(media.id) { mutableStateOf(Size.Zero) }
    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val transformed = updateVideoViewportTransform(
            scale = scale,
            offset = offset,
            centroid = centroid,
            viewportCenter = viewportSize.center,
            zoomChange = zoomChange,
            panChange = panChange,
        )
        scale = transformed.scale
        offset = transformed.offset
    }

    fun revealControls() {
        controlsVisible = true
        controlsInteraction += 1
    }

    LaunchedEffect(uri, visible) {
        if (visible && uri != null) {
            player?.run {
                stop()
                clearMediaItems()
                release()
            }
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
            qualityMenuExpanded = false
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
    LaunchedEffect(player, controlsInteraction) {
        if (player == null) {
            controlsVisible = false
            return@LaunchedEffect
        }
        controlsVisible = true
        delay(VIDEO_CONTROLS_HIDE_DELAY_MILLIS)
        controlsVisible = false
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
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                if (!attachOnlyWhenVisible) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                visible = shouldAttachInlineVideo(
                    visibleFraction(bounds.top, bounds.bottom, 0f, rootView.height.toFloat()),
                )
            }
            .testTag("$controlTagPrefix-${media.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it.toSize() }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .transformable(transformState)
                .pointerInput(media.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                }
                .testTag("$controlTagPrefix-viewport"),
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
                    .testTag(
                        if (player == null) "$controlTagPrefix-disconnected"
                        else "$controlTagPrefix-connected",
                    ),
            )
        }
        if (player != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = ::revealControls,
                    )
                    .testTag("$controlTagPrefix-surface"),
            )
        }
        if (player != null && controlsVisible) {
            var controlsModifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            if (fullscreen) controlsModifier = controlsModifier.navigationBarsPadding()
            Column(
                modifier = controlsModifier.testTag("$controlTagPrefix-controls"),
            ) {
                Slider(
                    value = if (duration > 0L) position.coerceAtMost(duration).toFloat() else 0f,
                    onValueChange = {
                        revealControls()
                        player?.seekTo(it.toLong())
                    },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.fillMaxWidth().testTag("$controlTagPrefix-seek"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            revealControls()
                            player?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                        modifier = Modifier.size(34.dp).testTag("$controlTagPrefix-play"),
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            stringResource(if (playing) R.string.media_viewer_pause else R.string.media_viewer_play),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = {
                            revealControls()
                            muted = !muted
                        },
                        modifier = Modifier.size(34.dp).testTag("$controlTagPrefix-mute"),
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
                            revealControls()
                            playbackVolume = it.coerceIn(0f, 1f)
                            if (playbackVolume > 0f) muted = false
                        },
                        modifier = Modifier.weight(1f).testTag("$controlTagPrefix-volume"),
                    )
                    if (media.variants.size > 1) {
                        Box {
                            IconButton(
                                onClick = {
                                    revealControls()
                                    qualityMenuExpanded = true
                                },
                                modifier = Modifier.size(34.dp).testTag("$controlTagPrefix-quality"),
                            ) {
                                Icon(
                                    Icons.Default.HighQuality,
                                    stringResource(R.string.video_quality_menu),
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = qualityMenuExpanded,
                                onDismissRequest = { qualityMenuExpanded = false },
                            ) {
                                VideoQuality.entries.forEach { quality ->
                                    DropdownMenuItem(
                                        text = { Text(videoQualityLabel(quality)) },
                                        onClick = {
                                            selectedQuality = quality
                                            qualityMenuExpanded = false
                                            revealControls()
                                        },
                                        modifier = Modifier.testTag(
                                            "$controlTagPrefix-quality-${quality.name.lowercase()}",
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            revealControls()
                            loopActive = !loopActive
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .semantics { selected = loopActive }
                            .testTag("$controlTagPrefix-loop"),
                    ) {
                        Icon(Icons.Default.Repeat, stringResource(R.string.setting_video_loop), tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            revealControls()
                            onFullscreen()
                        },
                        modifier = Modifier.size(34.dp).testTag("$controlTagPrefix-fullscreen"),
                    ) {
                        Icon(
                            if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            stringResource(
                                if (fullscreen) R.string.media_viewer_close
                                else R.string.inline_video_fullscreen,
                            ),
                            tint = Color.White,
                        )
                    }
                    onRotateToLandscape?.let { rotate ->
                        IconButton(
                            onClick = {
                                revealControls()
                                rotate()
                            },
                            modifier = Modifier.size(34.dp).testTag("$controlTagPrefix-rotate"),
                        ) {
                            Icon(
                                Icons.Default.ScreenRotation,
                                stringResource(R.string.media_viewer_rotate_landscape),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun videoQualityLabel(quality: VideoQuality): String = when (quality) {
    VideoQuality.AUTO -> stringResource(R.string.video_quality_auto)
    VideoQuality.LOW -> stringResource(R.string.video_quality_low)
    VideoQuality.MEDIUM -> stringResource(R.string.video_quality_medium)
    VideoQuality.HIGH -> stringResource(R.string.video_quality_high)
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
