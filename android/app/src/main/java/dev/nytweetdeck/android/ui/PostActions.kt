package dev.nytweetdeck.android.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostActionType

@Composable
internal fun PostActions(
    post: Post,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onCreateQuoteClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onImpressionClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onShareDetailsCopy: (String) -> Unit,
    onShareTranslatedCopy: (String, String?, String?) -> Unit,
    onDownloadClick: (String) -> Unit,
    pendingActions: Set<PostActionType>,
    failedActions: Set<PostActionType>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            PostActionButton(
                tag = "post-action-reply-" + post.id,
                label = stringResource(R.string.post_reply),
                icon = replyActionIcon(),
                count = post.replyCount,
                active = false,
                activeColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = { onReplyClick(post.id) },
            )
            RepostMenuButton(
                post = post,
                failed = PostActionType.REPOST in failedActions,
                modifier = Modifier.weight(1f),
                onRepostClick = onRepostClick,
                onQuoteClick = onCreateQuoteClick,
            )
            PostActionButton(
                tag = "post-action-like-" + post.id,
                label = stringResource(R.string.post_like),
                icon = if (post.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                count = post.likeCount,
                active = post.liked,
                activeColor = Color(0xFFF91880),
                celebrate = true,
                modifier = Modifier.weight(1f),
                onClick = { onLikeClick(post.id) },
                pending = false,
                failed = PostActionType.LIKE in failedActions,
            )
            PostActionButton(
                tag = "post-action-impressions-" + post.id,
                label = stringResource(R.string.post_impressions),
                icon = Icons.Default.Visibility,
                count = post.viewCount,
                active = false,
                activeColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = { onImpressionClick(post.id) },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            PostActionButton(
                tag = "post-action-bookmark-" + post.id,
                label = stringResource(R.string.post_bookmark),
                icon = if (post.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                count = post.bookmarkCount,
                active = post.bookmarked,
                activeColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = { onBookmarkClick(post.id) },
                pending = PostActionType.BOOKMARK in pendingActions,
                failed = PostActionType.BOOKMARK in failedActions,
            )
            ShareMenuButton(
                post = post,
                modifier = Modifier.weight(1f),
                onShareClick = onShareClick,
                onShareDetailsCopy = onShareDetailsCopy,
                onShareTranslatedCopy = onShareTranslatedCopy,
            )
            PostActionButton(
                tag = "post-action-download-" + post.id,
                label = stringResource(R.string.post_download),
                icon = Icons.Default.Download,
                count = null,
                active = false,
                activeColor = MaterialTheme.colorScheme.primary,
                enabled = post.media.any { it.url != null || it.previewUrl != null },
                modifier = Modifier.weight(1f),
                onClick = { onDownloadClick(post.id) },
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RepostMenuButton(
    post: Post,
    failed: Boolean,
    modifier: Modifier,
    onRepostClick: (String) -> Unit,
    onQuoteClick: (String) -> Unit,
) {
    var expanded by remember(post.id) { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        PostActionButton(
            tag = "post-action-repost-" + post.id,
            label = stringResource(R.string.post_repost),
            icon = Icons.Default.Repeat,
            count = post.repostCount + post.quoteCount,
            active = post.reposted,
            activeColor = Color(0xFF1D9BF0),
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = true },
            pending = false,
            failed = failed,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("repost-menu-" + post.id),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.post_repost)) },
                onClick = {
                    expanded = false
                    onRepostClick(post.id)
                },
                modifier = Modifier.testTag("repost-menu-repost-" + post.id),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.post_quote)) },
                onClick = {
                    expanded = false
                    onQuoteClick(post.id)
                },
                modifier = Modifier.testTag("repost-menu-quote-" + post.id),
            )
        }
    }
}

@Composable
private fun ShareMenuButton(
    post: Post,
    modifier: Modifier,
    onShareClick: (String) -> Unit,
    onShareDetailsCopy: (String) -> Unit,
    onShareTranslatedCopy: (String, String?, String?) -> Unit,
) {
    var expanded by remember(post.id) { mutableStateOf(false) }
    val absoluteTime = remember(post.createdAt) { formatShareAbsoluteTime(post.createdAt) }
    val relativeLabel = relativeTime(post.createdAt)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        PostActionButton(
            tag = "post-action-share-" + post.id,
            label = stringResource(R.string.post_share),
            icon = Icons.Default.Share,
            count = null,
            active = false,
            activeColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("share-menu-" + post.id),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.post_share_copy_url)) },
                onClick = {
                    expanded = false
                    onShareClick(post.id)
                },
                modifier = Modifier.testTag("share-menu-url-" + post.id),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.post_share_copy_details)) },
                onClick = {
                    expanded = false
                    formatDetailedPostShare(post, absoluteTime, relativeLabel)
                        ?.let(onShareDetailsCopy)
                        ?: onShareClick(post.id)
                },
                modifier = Modifier.testTag("share-menu-details-" + post.id),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.post_share_copy_details_translated)) },
                onClick = {
                    expanded = false
                    onShareTranslatedCopy(post.id, absoluteTime, relativeLabel)
                },
                modifier = Modifier.testTag("share-menu-details-translated-" + post.id),
            )
        }
    }
}

internal fun replyActionIcon(): androidx.compose.ui.graphics.vector.ImageVector =
    Icons.Outlined.ChatBubbleOutline

@Composable
private fun PostActionButton(
    tag: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Long?,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean = true,
    pending: Boolean = false,
    celebrate: Boolean = false,
    failed: Boolean = false,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var burstSequence by remember(tag) { mutableIntStateOf(0) }
    val burst = remember(tag) { Animatable(1f) }
    val reduceMotion = LocalPostReduceMotion.current
    LaunchedEffect(burstSequence) {
        if (burstSequence > 0) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(650))
        }
    }
    val tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconButton(
                onClick = {
                    if (celebrate && !active && !reduceMotion && ValueAnimator.areAnimatorsEnabled()) {
                        burstSequence += 1
                    }
                    onClick()
                },
                enabled = enabled,
                modifier = Modifier
                    .size(36.dp)
                    .testTag(tag),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                )
            }
            if (burstSequence > 0 && burst.value < 1f && !reduceMotion) {
                Canvas(Modifier.matchParentSize().testTag("$tag-fireworks")) {
                    val distance = size.minDimension * 0.45f * burst.value
                    repeat(10) { index ->
                        val angle = index * Math.PI * 2 / 10
                        drawCircle(
                            color = activeColor.copy(alpha = 1f - burst.value),
                            radius = 2.dp.toPx() * (1f - burst.value * 0.5f),
                            center = center + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat()),
                        )
                    }
                }
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp).testTag("$tag-pending"),
                    strokeWidth = 1.dp,
                )
            } else if (failed) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = stringResource(R.string.post_action_failed),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .testTag("$tag-failed"),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        count?.let {
            Text(
                text = engagementCount(it),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
        }
    }
}


internal val LocalPostReduceMotion = staticCompositionLocalOf { false }
