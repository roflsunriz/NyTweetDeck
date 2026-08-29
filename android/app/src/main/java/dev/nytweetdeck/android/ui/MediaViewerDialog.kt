package dev.nytweetdeck.android.ui

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl

@Composable
internal fun MediaViewerDialog(
    media: Media,
    videoAutoplay: Boolean,
    videoLoop: Boolean,
    videoVolume: Int,
    onDismiss: () -> Unit,
) {
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
                if (media.type == "photo") {
                    PhotoViewer(media)
                } else {
                    VideoViewer(
                        media = media,
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
private fun PhotoViewer(media: Media) {
    var scale by remember(media.id) { mutableStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.1f, 8f)
        offset = Offset(offset.x + panChange.x, offset.y + panChange.y)
    }
    val imageUri = remember(media.url, media.previewUrl) {
        safeMediaUri(media.url ?: media.previewUrl)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
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
                    .transformable(transformState),
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

@Composable
private fun VideoViewer(
    media: Media,
    videoAutoplay: Boolean,
    videoLoop: Boolean,
    videoVolume: Int,
) {
    val videoUri = remember(media.url) { safeMediaUri(media.url) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(true) }
    val volume = (videoVolume.coerceIn(0, 100) / 100f).takeIf { !muted } ?: 0f

    LaunchedEffect(mediaPlayer, videoLoop, volume) {
        mediaPlayer?.isLooping = videoLoop
        mediaPlayer?.setVolume(volume, volume)
    }
    DisposableEffect(videoView) {
        onDispose {
            videoView?.setOnPreparedListener(null)
            videoView?.setOnCompletionListener(null)
            videoView?.stopPlayback()
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
            AndroidView(
                factory = { context ->
                    VideoView(context).also { view ->
                        videoView = view
                        view.setBackgroundColor(android.graphics.Color.BLACK)
                        view.setVideoURI(videoUri)
                        view.setOnPreparedListener { player ->
                            mediaPlayer = player
                            isPrepared = true
                            player.isLooping = videoLoop
                            player.setVolume(0f, 0f)
                            if (videoAutoplay) {
                                view.start()
                                isPlaying = true
                            }
                        }
                        view.setOnCompletionListener {
                            isPlaying = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("media-video"),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    val view = videoView ?: return@IconButton
                    if (view.isPlaying) {
                        view.pause()
                        isPlaying = false
                    } else {
                        view.start()
                        isPlaying = true
                    }
                },
                enabled = isPrepared && videoUri != null,
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
                enabled = isPrepared && videoUri != null,
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
