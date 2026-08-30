package dev.nytweetdeck.android.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl

@Composable
internal fun MediaViewerDialog(
    media: Media,
    mediaItems: List<Media> = listOf(media),
    videoAutoplay: Boolean,
    videoLoop: Boolean,
    videoVolume: Int,
    onDismiss: () -> Unit,
) {
    val photos = remember(media.id, mediaItems) {
        buildList {
            mediaItems.filterTo(this) { it.type == "photo" }
            if (media.type == "photo" && none { it.id == media.id }) add(media)
        }.distinctBy(Media::id)
    }
    var photoIndex by remember(media.id, photos) {
        mutableIntStateOf(photos.indexOfFirst { it.id == media.id }.coerceAtLeast(0))
    }
    val activeMedia = if (media.type == "photo") photos.getOrNull(photoIndex) ?: media else media
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("media-viewer"),
            color = Color.Black,
        ) {
            Box(Modifier.fillMaxSize()) {
                if (activeMedia.type == "photo") {
                    PhotoViewer(
                        media = activeMedia,
                        index = photoIndex,
                        count = photos.size,
                        onNext = {
                            if (photos.size > 1) photoIndex = (photoIndex + 1) % photos.size
                        },
                        onPrevious = {
                            if (photos.size > 1) {
                                photoIndex = (photoIndex - 1 + photos.size) % photos.size
                            }
                        },
                    )
                } else {
                    VideoViewer(
                        media = activeMedia,
                        videoAutoplay = videoAutoplay,
                        videoLoop = videoLoop,
                        videoVolume = videoVolume,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .testTag("media-close"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.media_viewer_close),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoViewer(
    media: Media,
    index: Int,
    count: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    var scale by remember(media.id) { mutableStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var navigationLocked by remember { mutableStateOf(false) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(0.1f, 8f)
        val nextOffset = Offset(offset.x + panChange.x, offset.y + panChange.y)
        val switchThreshold = viewportWidth * 0.45f
        when {
            !navigationLocked && count > 1 && nextScale <= 1.05f &&
                switchThreshold > 0f && nextOffset.x <= -switchThreshold -> {
                navigationLocked = true
                scale = 1f
                offset = Offset.Zero
                onNext()
            }
            !navigationLocked && count > 1 && nextScale <= 1.05f &&
                switchThreshold > 0f && nextOffset.x >= switchThreshold -> {
                navigationLocked = true
                scale = 1f
                offset = Offset.Zero
                onPrevious()
            }
            else -> {
                scale = nextScale
                offset = nextOffset
            }
        }
    }
    LaunchedEffect(transformState) {
        snapshotFlow { transformState.isTransformInProgress }.collect { inProgress ->
            if (!inProgress) navigationLocked = false
        }
    }
    val imageUri = remember(media.url, media.previewUrl) {
        safeMediaUri(media.url ?: media.previewUrl)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged { viewportWidth = it.width.toFloat() }
            .testTag("media-image"),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUri == null) {
            Text(
                text = stringResource(R.string.media_viewer_unavailable),
                color = Color.White,
            )
        } else {
            AsyncImage(
                model = imageUri,
                contentDescription = stringResource(R.string.post_media),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(media.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                        )
                    }
                    .transformable(transformState)
                    .testTag("media-image-index-$index"),
                contentScale = ContentScale.Fit,
            )
        }
        IconButton(
            onClick = {
                scale = 1f
                offset = Offset.Zero
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .testTag("media-reset"),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.media_viewer_reset),
                tint = Color.White,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoViewer(
    media: Media,
    videoAutoplay: Boolean,
    videoLoop: Boolean,
    videoVolume: Int,
) {
    val videoUri = remember(media.url) { safeMediaUri(media.url) }
    val context = LocalContext.current
    val player = remember(media.id, videoUri) {
        videoUri?.let {
            CachedVideoPlayback.createPlayer(context).apply {
                setMediaItem(MediaItem.fromUri(it))
                playWhenReady = videoAutoplay
                prepare()
            }
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(true) }
    val volume = (videoVolume.coerceIn(0, 100) / 100f).takeIf { !muted } ?: 0f

    LaunchedEffect(player, videoLoop, volume) {
        player ?: return@LaunchedEffect
        player.repeatMode = if (videoLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = volume
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
        }
        player?.addListener(listener)
        isPlaying = player?.isPlaying == true
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (videoUri == null) {
            Text(
                text = stringResource(R.string.media_viewer_unavailable),
                color = Color.White,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("media-video"),
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
                        .testTag(if (isPlaying) "media-video-playing" else "media-video-paused"),
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    val activePlayer = player ?: return@IconButton
                    if (activePlayer.playWhenReady) {
                        activePlayer.pause()
                    } else {
                        if (activePlayer.playbackState == Player.STATE_ENDED) {
                            activePlayer.seekToDefaultPosition()
                        }
                        activePlayer.play()
                    }
                },
                enabled = videoUri != null,
                modifier = Modifier.testTag("media-play"),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.media_viewer_pause else R.string.media_viewer_play,
                    ),
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = { muted = !muted },
                enabled = videoUri != null,
                modifier = Modifier.testTag("media-mute"),
            ) {
                Icon(
                    imageVector = if (muted) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
                    contentDescription = stringResource(
                        if (muted) R.string.media_viewer_unmute else R.string.media_viewer_mute,
                    ),
                    tint = Color.White,
                )
            }
        }
    }
}

internal fun safeMediaUri(value: String?): Uri? {
    if (value.isNullOrBlank()) return null
    val verified = verifiedExternalHttpsUrl(value, setOf("twimg.com")) ?: return null
    return Uri.parse(verified)
}
