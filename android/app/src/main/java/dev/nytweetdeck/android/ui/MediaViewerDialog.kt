package dev.nytweetdeck.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.AsyncImage
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.VideoQuality
import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl

@Composable
internal fun MediaViewerDialog(
    media: Media,
    mediaItems: List<Media> = listOf(media),
    videoAutoplay: Boolean,
    videoLoop: Boolean,
    videoVolume: Int,
    videoQuality: VideoQuality = VideoQuality.AUTO,
    onDismiss: () -> Unit,
) {
    val photos = remember(media.id, mediaItems) {
        buildList {
            mediaItems.filterTo(this) { it.type == "photo" }
            if (media.type == "photo" && none { it.id == media.id }) add(media)
        }.distinctBy(Media::id)
    }
    val activity = LocalContext.current.findActivity()
    val dismissViewer = {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDismiss()
    }
    DisposableEffect(activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    Dialog(
        onDismissRequest = dismissViewer,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ImmersiveMediaWindow()
        FullScreenRouteSurface(
            tag = "media-viewer",
            onDismiss = dismissViewer,
            color = Color.Black,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    if (media.type == "photo") {
                        PhotoViewer(
                            initialMedia = media,
                            mediaItems = photos,
                        )
                    } else {
                        FullscreenVideoPlayer(
                            media = media,
                            autoPlay = videoAutoplay,
                            loop = videoLoop,
                            volume = videoVolume,
                            defaultQuality = videoQuality,
                            onExitFullscreen = dismissViewer,
                            onRotateToLandscape = {
                                activity?.requestedOrientation =
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            },
                            modifier = Modifier.fillMaxSize().testTag("media-video"),
                        )
                    }
                    IconButton(
                        onClick = dismissViewer,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End,
                                ),
                            )
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
}

@Composable
private fun ImmersiveMediaWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun PhotoViewer(
    initialMedia: Media,
    mediaItems: List<Media>,
) {
    var currentIndex by remember(initialMedia.id, mediaItems) {
        mutableIntStateOf(mediaItems.indexOfFirst { it.id == initialMedia.id }.coerceAtLeast(0))
    }
    var scale by remember(initialMedia.id, mediaItems) { mutableStateOf(1f) }
    var offset by remember(initialMedia.id, mediaItems) { mutableStateOf(Offset.Zero) }
    val offsetAnim = remember(initialMedia.id, mediaItems) {
        Animatable(Offset.Zero, Offset.VectorConverter)
    }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var navigationLocked by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Keep animatable in sync with drag offset for spring
    LaunchedEffect(offset) {
        if (!offsetAnim.isRunning) offsetAnim.snapTo(offset)
    }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(0.1f, 8f)
        val isZoomed = nextScale > 1.05f
        val rawNextOffset = Offset(offset.x + panChange.x, offset.y + panChange.y)
        val nextOffset = if (isZoomed) rawNextOffset else Offset(rawNextOffset.x, 0f)
        val pageDirection = if (isZoomed) 0 else {
            photoPageDirectionForDrag(nextOffset.x, viewportWidth)
        }
        when {
            !navigationLocked && mediaItems.size > 1 && pageDirection != 0 -> {
                navigationLocked = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentIndex = wrappedPhotoIndex(currentIndex, mediaItems.size, pageDirection)
                scale = 1f
                offset = Offset(
                    nextOffset.x + if (pageDirection > 0) viewportWidth else -viewportWidth,
                    0f,
                )
            }
            else -> {
                scale = nextScale
                offset = nextOffset
            }
        }
    }
    LaunchedEffect(transformState) {
        snapshotFlow { transformState.isTransformInProgress }.collect { inProgress ->
            if (!inProgress) {
                navigationLocked = false
                if (scale <= 1.05f && offset != Offset.Zero) {
                    scope.launch {
                        offsetAnim.snapTo(offset)
                        offsetAnim.animateTo(
                            Offset.Zero,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                        offset = Offset.Zero
                    }
                }
            }
        }
    }
    val displayOffset = if (transformState.isTransformInProgress) offset else offsetAnim.value
    val currentMedia = mediaItems.getOrNull(currentIndex) ?: initialMedia
    val previousIndex = wrappedPhotoIndex(currentIndex, mediaItems.size, -1)
    val nextIndex = wrappedPhotoIndex(currentIndex, mediaItems.size, 1)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged { viewportWidth = it.width.toFloat() }
            .testTag("media-image"),
        contentAlignment = Alignment.Center,
    ) {
        if (mediaItems.size > 1) {
            PhotoPage(
                media = mediaItems[previousIndex],
                translationX = displayOffset.x - viewportWidth,
                testTag = "media-image-previous-index-$previousIndex",
            )
            PhotoPage(
                media = mediaItems[nextIndex],
                translationX = displayOffset.x + viewportWidth,
                testTag = "media-image-next-index-$nextIndex",
            )
        }
        PhotoPage(
            media = currentMedia,
            translationX = displayOffset.x,
            translationY = displayOffset.y,
            scale = scale,
            testTag = "media-image-index-$currentIndex",
            unavailableMessage = stringResource(R.string.media_viewer_unavailable),
            contentDescription = stringResource(R.string.post_media),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentMedia.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                            scope.launch { offsetAnim.snapTo(Offset.Zero) }
                        },
                    )
                }
                .transformable(transformState),
        )
        IconButton(
            onClick = {
                scale = 1f
                offset = Offset.Zero
                scope.launch { offsetAnim.snapTo(Offset.Zero) }
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
private fun PhotoPage(
    media: Media,
    translationX: Float,
    testTag: String,
    translationY: Float = 0f,
    scale: Float = 1f,
    unavailableMessage: String? = null,
    contentDescription: String? = null,
) {
    val imageUri = remember(media.id, media.url, media.previewUrl) {
        safeMediaUri(media.url ?: media.previewUrl)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.translationX = translationX
                this.translationY = translationY
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUri == null) {
            unavailableMessage?.let { Text(text = it, color = Color.White) }
        } else {
            AsyncImage(
                model = imageUri,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

internal fun photoPageDirectionForDrag(dragOffset: Float, viewportWidth: Float): Int = when {
    viewportWidth <= 0f -> 0
    dragOffset < -viewportWidth / 2f -> 1
    dragOffset > viewportWidth / 2f -> -1
    else -> 0
}

internal fun wrappedPhotoIndex(currentIndex: Int, count: Int, pageDirection: Int): Int {
    if (count <= 0) return 0
    return (currentIndex + pageDirection).mod(count)
}

internal fun safeMediaUri(value: String?): Uri? {
    if (value.isNullOrBlank()) return null
    val verified = verifiedExternalHttpsUrl(value, setOf("twimg.com")) ?: return null
    return Uri.parse(verified)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
