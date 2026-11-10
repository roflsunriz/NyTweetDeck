package dev.nytweetdeck.android.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.VideoQuality
import dev.nytweetdeck.android.model.selectVideoUrl
import kotlin.math.max
import kotlin.math.min
import java.util.UUID
import kotlinx.coroutines.delay

private const val INLINE_PLAYBACK_VISIBLE_FRACTION = 0.6f
private const val VIDEO_MIN_SCALE = 1f
private const val VIDEO_MAX_SCALE = 4f
internal const val VIDEO_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
internal val VideoZoomScale = SemanticsPropertyKey<Float>("VideoZoomScale")

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
    if (!scale.isFinite() || scale <= 0f || !zoomChange.isFinite() || zoomChange <= 0f) {
        return VideoViewportTransform(VIDEO_MIN_SCALE, Offset.Zero)
    }
    val nextScale = (scale * zoomChange).coerceIn(VIDEO_MIN_SCALE, VIDEO_MAX_SCALE)
    val effectiveCentroid = centroid.takeIf { it.isSpecified } ?: viewportCenter
    val scaleRatio = nextScale / scale
    val translated = effectiveCentroid - (effectiveCentroid - offset) * scaleRatio + panChange
    return VideoViewportTransform(
        scale = nextScale,
        offset = if (nextScale <= VIDEO_MIN_SCALE) {
            Offset.Zero
        } else {
            Offset(
                translated.x.coerceIn(-viewportCenter.x.coerceAtLeast(0f) * 2f * (nextScale - 1f), 0f),
                translated.y.coerceIn(-viewportCenter.y.coerceAtLeast(0f) * 2f * (nextScale - 1f), 0f),
            )
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
    suspended: Boolean = false,
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
        suspended = suspended,
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
    suspended: Boolean = false,
) {
    val owner = remember(media.id) { UUID.randomUUID().toString() }
    val initialState = remember(media.id) { VideoPlaybackStates.get(media.id) }
    var selectedQuality by remember(media.id) { mutableStateOf(initialState?.quality ?: defaultQuality) }
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
    var muted by remember(media.id) { mutableStateOf(initialState?.muted ?: true) }
    var loopActive by remember(media.id) { mutableStateOf(initialState?.loop ?: loop) }
    var playbackVolume by remember(media.id, volume) {
        mutableFloatStateOf(initialState?.volume ?: (volume.coerceIn(0, 100) / 100f))
    }
    var position by remember(media.id) { mutableLongStateOf(0L) }
    var duration by remember(media.id) { mutableLongStateOf(0L) }
    var controlsVisible by remember(media.id) { mutableStateOf(true) }
    var controlsInteraction by remember(media.id) { mutableIntStateOf(0) }
    var qualityMenuExpanded by remember(media.id) { mutableStateOf(false) }
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(media.id) { mutableStateOf(Size.Zero) }
    var playbackFailed by remember(media.id) { mutableStateOf(false) }
    var retryCount by remember(media.id) { mutableIntStateOf(0) }
    var boundPlayerView by remember(media.id) { mutableStateOf<PlayerView?>(null) }
    var mediaAttached by remember(media.id) { mutableStateOf(false) }
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

    fun savePlayback() {
        val current = player ?: return
        if (current.mediaItemCount == 0) return
        position = current.currentPosition.coerceAtLeast(0L)
        VideoPlaybackStates.save(media.id, VideoPlaybackSnapshot(
            position = position,
            playWhenReady = current.playWhenReady,
            muted = muted,
            volume = playbackVolume,
            quality = selectedQuality,
            loop = loopActive,
        ))
    }

    fun startPlayback() {
        val current = player ?: return
        VideoPlaybackCoordinator.claim(owner)
        if (current.mediaItemCount == 0 && uri != null) {
            position = VideoPlaybackStates.get(media.id)?.position ?: position
            current.setMediaItem(MediaItem.fromUri(uri), position)
            current.prepare()
            mediaAttached = true
        }
        current.play()
    }

    // View間のhandoffはstateを保存してからcodec/surfaceを切り離す。
    val attached = visible && !suspended
    DisposableEffect(uri, attached, retryCount) {
        if (attached && uri != null) {
            VideoPlaybackCoordinator.claim(owner)
            val restored = VideoPlaybackStates.get(media.id)
            restored?.let {
                muted = it.muted
                playbackVolume = it.volume
                loopActive = it.loop
                position = it.position
                selectedQuality = it.quality
            }
            playbackFailed = false
            try {
                val created = CachedVideoPlayback.createPlayer(context)
                player = created
                created.apply {
                    this.volume = if (muted) 0f else playbackVolume
                    repeatMode = if (loopActive) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    setMediaItem(MediaItem.fromUri(uri), restored?.position ?: position)
                    mediaAttached = true
                    prepare()
                    playWhenReady = restored?.playWhenReady ?: autoPlay
                }
            } catch (_: RuntimeException) {
                player?.release()
                player = null
                playbackFailed = true
            }
        }
        onDispose {
            savePlayback()
            boundPlayerView?.player = null
            player?.release()
            player = null
            mediaAttached = false
            playing = false
            qualityMenuExpanded = false
            VideoPlaybackCoordinator.release(owner)
        }
    }
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
                if (value) VideoPlaybackCoordinator.claim(owner)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = activePlayer?.duration?.takeIf { it > 0L } ?: 0L
                mediaAttached = activePlayer?.mediaItemCount?.let { it > 0 } == true
            }

            override fun onPlayerError(error: PlaybackException) {
                savePlayback()
                playbackFailed = true
                playing = false
            }
        }
        activePlayer?.addListener(listener)
        playing = activePlayer?.isPlaying == true
        onDispose {
            activePlayer?.removeListener(listener)
            playing = false
        }
    }
    DisposableEffect(owner) {
        val subscription = VideoPlaybackCoordinator.observe { activeId ->
            if (activeId != null && activeId != owner) {
                savePlayback()
                // Pausing alone retains decoders and buffers in every visible column.
                player?.stop()
                player?.clearMediaItems()
                mediaAttached = false
                playing = false
            }
        }
        onDispose {
            subscription.close()
            VideoPlaybackCoordinator.release(owner)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            // PlayerViewのFIT余白は透明なので、下のCropプレビューを接続中だけ覆う。
            .background(if (mediaAttached) Color.Black else Color.Transparent)
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
                .testTag("$controlTagPrefix-viewport"),
        ) {
            if (player == null) {
                Box(Modifier.fillMaxSize().testTag("$controlTagPrefix-disconnected"))
            } else AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        this.player = player
                        boundPlayerView = this
                        setKeepContentOnPlayerReset(false)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = {
                    boundPlayerView = it
                    it.player = player
                    it.visibility = if (mediaAttached) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    // Androidは同じWindow内のViewの要求を集約する。共有rootへ直接設定せず、
                    // 各Viewを再生中だけ保持することで別プレイヤーの解除と競合させない。
                    it.keepScreenOn = attached && playing && player?.isPlaying == true
                },
                onRelease = {
                    it.keepScreenOn = false
                    it.player = null
                    if (boundPlayerView === it) boundPlayerView = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("$controlTagPrefix-connected"),
            )
        }
        if (player != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Gesture detection belongs above the native PlayerView, on the same
                    // surface as taps; a clickable overlay used to intercept every pinch.
                    .transformable(transformState, canPan = { scale > VIDEO_MIN_SCALE })
                    .pointerInput(media.id) {
                        detectTapGestures(
                            // Show controls on contact, before the double-tap decision delays onTap.
                            onPress = { revealControls() },
                            onDoubleTap = {
                                scale = VIDEO_MIN_SCALE
                                offset = Offset.Zero
                                revealControls()
                            },
                            onTap = {
                                // 解除のタップでは再生状態を変えない。
                                if (muted) {
                                    muted = false
                                } else {
                                    player?.let { if (it.playWhenReady) it.pause() else startPlayback() }
                                }
                                revealControls()
                            },
                        )
                    }
                    .semantics { this[VideoZoomScale] = scale }
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
                Text(
                    text = "${formatVideoTime(position)} / ${formatVideoTime(duration)}",
                    color = Color.White,
                    modifier = Modifier.testTag("$controlTagPrefix-time"),
                )
                Slider(
                    value = if (duration > 0L) position.coerceAtMost(duration).toFloat() else 0f,
                    onValueChange = {
                        revealControls()
                        muted = false
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
                            if (playbackFailed) {
                                retryCount += 1
                            } else player?.let { if (it.playWhenReady) it.pause() else startPlayback() }
                        },
                        modifier = Modifier.size(34.dp).semantics { selected = playing }
                            .testTag("$controlTagPrefix-play"),
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
                        modifier = Modifier.size(34.dp).semantics { selected = muted }
                            .testTag("$controlTagPrefix-mute"),
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
                            muted = false
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
                                            savePlayback()
                                            selectedQuality = quality
                                            VideoPlaybackStates.get(media.id)?.let {
                                                VideoPlaybackStates.save(media.id, it.copy(quality = quality))
                                            }
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
                            savePlayback()
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
        if (playbackFailed) {
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f))
                    .padding(12.dp).testTag("$controlTagPrefix-error"),
            ) {
                Text(stringResource(R.string.video_playback_error), color = Color.White)
                TextButton(onClick = { retryCount += 1 }) { Text(stringResource(R.string.retry)) }
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
