package dev.nytweetdeck.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.EmbeddedPost
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostActionType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun PostCard(
    post: Post,
    onPostClick: (String) -> Unit = {},
    onQuoteClick: (String) -> Unit = {},
    onReplyClick: (String) -> Unit = {},
    onRepostClick: (String) -> Unit = {},
    onLikeClick: (String) -> Unit = {},
    onImpressionClick: (String) -> Unit = {},
    onBookmarkClick: (String) -> Unit = {},
    onShareClick: (String) -> Unit = {},
    onDownloadClick: (String) -> Unit = {},
    pendingActions: Set<PostActionType> = emptySet(),
    failedActions: Set<PostActionType> = emptySet(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPostClick(post.id) }
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("post-" + post.id),
    ) {
        post.repostedBy?.let { repostedBy ->
            RepostContext(repostedBy)
            Spacer(Modifier.height(6.dp))
        }
        PostAuthorHeader(post.author, post.createdAt)
        ReplyContext(post)
        Spacer(Modifier.height(8.dp))
        PostBody(
            text = displayPostText(post.text, post.preTranslated?.text),
            tag = "post-body-" + post.id,
        )
        if (post.media.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MediaPreview(post.media, post.id, compact = false)
        }
        post.article?.let { article ->
            Spacer(Modifier.height(10.dp))
            ArticlePreview(article, compact = false)
        }
        post.quotedPost?.let { quote ->
            Spacer(Modifier.height(10.dp))
            QuoteCard(
                parentPostId = post.id,
                quote = quote,
                onQuoteClick = onQuoteClick,
            )
        }
        Spacer(Modifier.height(8.dp))
        PostActions(
            post = post,
            onReplyClick = onReplyClick,
            onRepostClick = onRepostClick,
            onLikeClick = onLikeClick,
            onImpressionClick = onImpressionClick,
            onBookmarkClick = onBookmarkClick,
            onShareClick = onShareClick,
            onDownloadClick = onDownloadClick,
            pendingActions = pendingActions,
            failedActions = failedActions,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun RepostContext(author: Author) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Repeat,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.post_reposted_by, authorHandle(author)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PostAuthorHeader(author: Author, createdAt: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AuthorAvatar(author, modifier = Modifier.size(42.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = authorDisplayName(author),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (author.verified) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "@" + authorHandle(author),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = relativeTime(createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AuthorAvatar(author: Author, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = authorDisplayName(author).take(1).uppercase(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        author.avatarUrl?.let { avatarUrl ->
            AsyncImage(
                model = safeImageUrl(avatarUrl),
                contentDescription = stringResource(R.string.post_avatar, authorDisplayName(author)),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ReplyContext(post: Post) {
    val replyTo = post.replyToUsername?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)
    val section = post.conversationSection?.trim()?.takeIf(String::isNotBlank)
    if (replyTo == null && section == null) return
    Spacer(Modifier.height(6.dp))
    Column {
        replyTo?.let {
            Text(
                text = stringResource(R.string.post_replying_to, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        section?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PostBody(text: String, tag: String) {
    val hashtagColor = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            appendHashtagStyledText(text, hashtagColor)
        },
        modifier = Modifier.testTag(tag),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun QuoteCard(
    parentPostId: String,
    quote: EmbeddedPost,
    onQuoteClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .clickable { onQuoteClick(quote.id) }
            .testTag("post-quote-" + parentPostId + "-" + quote.id)
            .padding(10.dp),
    ) {
        Text(
            text = stringResource(R.string.post_quote),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        PostAuthorHeader(quote.author, quote.createdAt)
        Spacer(Modifier.height(6.dp))
        PostBody(
            text = displayPostText(quote.text, quote.preTranslated?.text),
            tag = "post-quote-body-" + quote.id,
        )
        if (quote.media.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            MediaPreview(quote.media, quote.id, compact = true)
        }
        quote.article?.let { article ->
            Spacer(Modifier.height(8.dp))
            ArticlePreview(article, compact = true)
        }
    }
}

@Composable
private fun MediaPreview(media: List<Media>, postId: String, compact: Boolean) {
    val visibleMedia = media.take(4)
    if (visibleMedia.size == 1) {
        MediaTile(
            media = visibleMedia.single(),
            postId = postId,
            height = if (compact) 120.dp else 220.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val rowHeight = if (compact) 96.dp else 132.dp
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        visibleMedia.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                row.forEach { item ->
                    MediaTile(
                        media = item,
                        postId = postId,
                        height = rowHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MediaTile(media: Media, postId: String, height: Dp, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(height)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("post-media-" + postId + "-" + media.id),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = safeImageUrl(media.previewUrl ?: media.url),
            contentDescription = stringResource(R.string.post_media),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (media.type != "photo") {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.post_media),
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .padding(6.dp),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ArticlePreview(article: Article, compact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            article.coverImageUrl?.let { coverUrl ->
                AsyncImage(
                    model = safeImageUrl(coverUrl),
                    contentDescription = null,
                    modifier = Modifier
                        .size(if (compact) 48.dp else 64.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                article.previewText?.takeIf(String::isNotBlank)?.let { preview ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PostActions(
    post: Post,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onImpressionClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    pendingActions: Set<PostActionType>,
    failedActions: Set<PostActionType>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            PostActionButton(
                tag = "post-action-reply-" + post.id,
                label = stringResource(R.string.post_reply),
                icon = Icons.AutoMirrored.Filled.Reply,
                count = post.replyCount,
                active = false,
                activeColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = { onReplyClick(post.id) },
            )
            PostActionButton(
                tag = "post-action-repost-" + post.id,
                label = stringResource(R.string.post_repost),
                icon = Icons.Default.Repeat,
                count = post.repostCount + post.quoteCount,
                active = post.reposted,
                activeColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = { onRepostClick(post.id) },
                pending = PostActionType.REPOST in pendingActions,
                failed = PostActionType.REPOST in failedActions,
            )
            PostActionButton(
                tag = "post-action-like-" + post.id,
                label = stringResource(R.string.post_like),
                icon = if (post.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                count = post.likeCount,
                active = post.liked,
                activeColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
                onClick = { onLikeClick(post.id) },
                pending = PostActionType.LIKE in pendingActions,
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
            PostActionButton(
                tag = "post-action-share-" + post.id,
                label = stringResource(R.string.post_share),
                icon = Icons.Default.Share,
                count = null,
                active = false,
                activeColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = { onShareClick(post.id) },
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
private fun PostActionButton(
    tag: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Long?,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean = true,
    pending: Boolean = false,
    failed: Boolean = false,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onClick,
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

@Composable
private fun relativeTime(createdAt: String?): String {
    val instant = parsePostInstant(createdAt) ?: return stringResource(R.string.post_time_unknown)
    val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60L -> stringResource(R.string.post_time_now)
        seconds < 3_600L -> stringResource(R.string.post_time_minutes, seconds / 60L)
        seconds < 86_400L -> stringResource(R.string.post_time_hours, seconds / 3_600L)
        seconds < 604_800L -> stringResource(R.string.post_time_days, seconds / 86_400L)
        else -> {
            val local = instant.atZone(ZoneId.systemDefault())
            stringResource(R.string.post_time_date, local.monthValue, local.dayOfMonth)
        }
    }
}

internal fun parsePostInstant(createdAt: String?): Instant? {
    val value = createdAt?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, X_CREATED_AT_FORMAT).toInstant() }.getOrNull()
}

private val X_CREATED_AT_FORMAT =
    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)

private fun displayPostText(original: String, translated: String?): String =
    translated?.takeIf(String::isNotBlank) ?: original

private fun authorDisplayName(author: Author): String =
    author.displayName.trim().ifBlank { author.username.trim().ifBlank { author.id } }

private fun authorHandle(author: Author): String =
    author.username.trim().removePrefix("@").ifBlank { author.id }

private fun engagementCount(value: Long): String {
    val normalized = value.coerceAtLeast(0)
    return when {
        normalized >= 1_000_000L -> (normalized / 1_000_000L).toString() + "M"
        normalized >= 1_000L -> (normalized / 1_000L).toString() + "K"
        else -> normalized.toString()
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendHashtagStyledText(
    text: String,
    hashtagColor: Color,
) {
    var cursor = 0
    HASHTAG_PATTERN.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        withStyle(SpanStyle(color = hashtagColor, fontWeight = FontWeight.Medium)) {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

private val HASHTAG_PATTERN = Regex("""#[\p{L}\p{N}_]+""")
