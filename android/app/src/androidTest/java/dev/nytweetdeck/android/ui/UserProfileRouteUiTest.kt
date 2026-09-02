package dev.nytweetdeck.android.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import dev.nytweetdeck.android.MainActivity
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.UserProfilePage
import dev.nytweetdeck.android.model.UserProfileStatus
import dev.nytweetdeck.android.model.UserProfileUiState
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Rule
import org.junit.Test

class UserProfileRouteUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun longProfileHeaderScrollsAwaySoPostsRemainReachable() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                UserProfileRoute(
                    state = profileState(),
                    onDismiss = {},
                    onRetry = {},
                    onTabSelected = {},
                    onLoadMore = {},
                    onPostClick = {},
                    onQuoteClick = {},
                    onCreateQuoteClick = {},
                    onAuthorClick = {},
                    onReplyClick = {},
                    onRepostClick = {},
                    onLikeClick = {},
                    onBookmarkClick = {},
                    onShareClick = {},
                    onDownloadClick = {},
                    onArticleClick = { _, _ -> },
                    onPostMenuClick = {},
                    translationStates = emptyMap(),
                    autoTranslatePosts = false,
                    onTranslationNeeded = {},
                    onTranslationRetry = {},
                    onToggleOriginal = {},
                    mediaPreview = false,
                    videoAutoplay = false,
                    videoLoop = true,
                    videoVolume = 100,
                )
            }
        }

        composeRule.onNodeWithTag("user-profile-header").assertIsDisplayed()
        composeRule.onNodeWithTag("user-profile-posts")
            .performScrollToNode(hasTestTag("post-profile-post"))
        composeRule.onNodeWithTag("post-profile-post").assertIsDisplayed()
    }

    private fun profileState() = UserProfileUiState(
        status = UserProfileStatus.READY,
        userId = "profile-user",
        profile = UserProfilePage(
            id = "profile-user",
            username = "profile_user",
            displayName = "Profile User",
            description = List(24) { "長いプロフィールでも一覧へ移動できる説明文です。" }.joinToString("\n"),
            avatarUrl = null,
            bannerUrl = null,
            createdAt = null,
            location = null,
            website = null,
            followingCount = 10,
            followerCount = 20,
            mutualFollowerCount = 3,
            mutualFollowers = emptyList(),
            verified = false,
            following = false,
            followsYou = false,
            muting = false,
            blocking = false,
        ),
        posts = listOf(
            Post(
                id = "profile-post",
                text = "プロフィールのポスト一覧",
                language = "ja",
                createdAt = null,
                author = Author("profile-user", "profile_user", "Profile User", null, false),
                repostedBy = null,
                conversationSection = null,
                replyCount = 0,
                repostCount = 0,
                quoteCount = 0,
                likeCount = 0,
                bookmarkCount = 0,
                viewCount = 0,
                liked = false,
                reposted = false,
                bookmarked = false,
                replyToPostId = null,
                replyToUsername = null,
                quotedPostId = null,
                quotedPost = null,
                communityNote = null,
                preTranslated = null,
                article = null,
                media = emptyList(),
            ),
        ),
    )
}
