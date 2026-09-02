package dev.nytweetdeck.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationCandidate
import dev.nytweetdeck.android.model.UserProfileStatus
import dev.nytweetdeck.android.model.UserProfileTab
import dev.nytweetdeck.android.model.UserProfileUiState
import dev.nytweetdeck.android.model.VideoQuality

@Composable
internal fun UserProfileRoute(
    state: UserProfileUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onTabSelected: (UserProfileTab) -> Unit,
    onLoadMore: () -> Unit,
    onPostClick: (String) -> Unit,
    onQuoteClick: (String) -> Unit,
    onCreateQuoteClick: (String) -> Unit,
    onAuthorClick: (Author) -> Unit,
    onReplyClick: (String) -> Unit,
    onRepostClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onShareClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    onArticleClick: (String, Article) -> Unit,
    onPostMenuClick: (Post) -> Unit,
    translationStates: Map<String, PostTranslationUiState>,
    autoTranslatePosts: Boolean,
    onTranslationNeeded: (TranslationCandidate) -> Unit,
    onTranslationRetry: (TranslationCandidate) -> Unit,
    onToggleOriginal: (String) -> Unit,
    mediaPreview: Boolean,
    videoAutoplay: Boolean,
    videoLoop: Boolean,
    videoVolume: Int,
    videoQuality: VideoQuality = VideoQuality.AUTO,
) {
    if (state.status == UserProfileStatus.CLOSED) return
    FullScreenRouteSurface(tag = "user-profile-route", onDismiss = onDismiss) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.profile?.displayName ?: stringResource(R.string.profile),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("close-user-profile")) {
                    Icon(Icons.Default.Close, stringResource(R.string.close))
                }
            }
            HorizontalDivider()
            state.profile?.let { profile ->
                Column(Modifier.fillMaxWidth().testTag("user-profile-header")) {
                    Box(Modifier.fillMaxWidth().height(112.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        profile.bannerUrl?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = profile.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(profile.displayName.orEmpty(), fontWeight = FontWeight.Bold)
                            Text("@${profile.username.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    profile.description?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text("${profile.followingCount} ${stringResource(R.string.following)}")
                        Text("${profile.followerCount} ${stringResource(R.string.profile_followers)}")
                        Text("${profile.mutualFollowerCount} ${stringResource(R.string.profile_mutual)}")
                    }
                }
            }
            PrimaryScrollableTabRow(selectedTabIndex = state.tab.ordinal) {
                UserProfileTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.testTag("profile-tab-" + tab.name.lowercase()),
                        text = { Text(profileTabLabel(tab)) },
                    )
                }
            }
            when {
                state.status == UserProfileStatus.LOADING && state.posts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.testTag("user-profile-loading"))
                    }
                }
                state.status == UserProfileStatus.FAILED && state.posts.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.profile_load_failed), color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRetry, modifier = Modifier.testTag("user-profile-retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize().testTag("user-profile-posts")) {
                    items(state.posts, key = Post::id) { post ->
                        PostCard(
                            post = post,
                            onPostClick = onPostClick,
                            onQuoteClick = onQuoteClick,
                            onCreateQuoteClick = onCreateQuoteClick,
                            onAuthorClick = onAuthorClick,
                            onReplyClick = onReplyClick,
                            onRepostClick = onRepostClick,
                            onLikeClick = onLikeClick,
                            onBookmarkClick = onBookmarkClick,
                            onShareClick = onShareClick,
                            onDownloadClick = onDownloadClick,
                            onArticleClick = onArticleClick,
                            onMenuClick = onPostMenuClick,
                            translationStates = translationStates,
                            autoTranslatePosts = autoTranslatePosts,
                            onTranslationNeeded = onTranslationNeeded,
                            onTranslationRetry = onTranslationRetry,
                            onToggleOriginal = onToggleOriginal,
                            pendingActions = emptySet<PostActionType>(),
                            mediaPreview = mediaPreview,
                            videoAutoplay = videoAutoplay,
                            videoLoop = videoLoop,
                            videoVolume = videoVolume,
                            videoQuality = videoQuality,
                        )
                    }
                    if (state.nextCursor != null || state.loadMoreFailed) {
                        item(key = "profile-load-more") {
                            Button(
                                onClick = onLoadMore,
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("user-profile-load-more"),
                            ) {
                                if (state.isLoadingMore) CircularProgressIndicator(Modifier.size(18.dp))
                                else Text(stringResource(if (state.loadMoreFailed) R.string.retry else R.string.profile_load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun profileTabLabel(tab: UserProfileTab): String = stringResource(
    when (tab) {
        UserProfileTab.ALL -> R.string.profile_tab_all
        UserProfileTab.POSTS -> R.string.profile_tab_posts
        UserProfileTab.HIGHLIGHTS -> R.string.profile_tab_highlights
        UserProfileTab.REPLIES -> R.string.profile_tab_replies
        UserProfileTab.MEDIA -> R.string.profile_tab_media
    },
)
