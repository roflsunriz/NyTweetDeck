package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.ui.PostCard
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MediaViewerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun showPhotoPost() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                PostCard(post = photoPost())
            }
        }
    }

    @Test
    fun photoViewerOpensResetsAndClosesOnAquos() {
        composeRule.onNodeWithTag("post-media-1-photo-1").performClick()
        composeRule.onNodeWithTag("media-viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("media-image").assertIsDisplayed()
        composeRule.onNodeWithTag("media-image-index-0", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("media-image").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("media-image-index-1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("media-image").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("media-image-index-0", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("media-image").performTouchInput { doubleClick() }
        composeRule.onNodeWithTag("media-reset").performClick()
        composeRule.onNodeWithTag("media-close").performClick()
        composeRule.onNodeWithTag("post-1").assertIsDisplayed()
    }

    @Test
    fun videoViewerUsesDedicatedPlayerControlsOnAquos() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                PostCard(
                    post = photoPost().copy(
                        media = listOf(
                            Media(
                                "video-1",
                                "video",
                                "https://video.twimg.com/ext_tw_video/fixture.mp4",
                                "https://pbs.twimg.com/media/fixture.jpg",
                            ),
                        ),
                    ),
                    videoAutoplay = false,
                    videoLoop = false,
                    videoVolume = 42,
                )
            }
        }
        composeRule.onNodeWithTag("inline-video-video-1").assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-play").assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-seek").assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-volume").assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-fullscreen").assertIsDisplayed()
        composeRule.onNodeWithTag("post-media-1-video-1").performClick()
        composeRule.onNodeWithTag("media-viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("media-video").assertIsDisplayed()
        composeRule.onNodeWithTag("media-play").assertIsDisplayed()
        composeRule.onNodeWithTag("media-play").assertIsEnabled()
        composeRule.onNodeWithTag("media-mute").assertIsDisplayed()
        composeRule.onNodeWithTag("media-mute").assertIsEnabled()
        composeRule.onNodeWithTag("media-close").performClick()
    }

    @Test
    fun autoplayVideoCreatesAnInlineMutedPlayerWhileVisible() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                PostCard(
                    post = photoPost().copy(
                        media = listOf(
                            Media(
                                "autoplay-video",
                                "animated_gif",
                                "https://video.twimg.com/ext_tw_video/fixture.mp4",
                                "https://pbs.twimg.com/media/fixture.jpg",
                            ),
                        ),
                    ),
                    videoAutoplay = true,
                    videoLoop = true,
                )
            }
        }

        composeRule.onNodeWithTag("inline-video-autoplay-video").assertIsDisplayed()
    }

    private fun photoPost() = Post(
        id = "1",
        text = "Photo fixture",
        language = "en",
        createdAt = "2026-08-29T00:00:00Z",
        author = Author("7", "fixture", "Fixture", null, false),
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
        media = listOf(
            Media(
                id = "photo-1",
                type = "photo",
                url = "https://pbs.twimg.com/media/fixture.jpg",
                previewUrl = "https://pbs.twimg.com/media/fixture.jpg",
            ),
            Media(
                id = "photo-2",
                type = "photo",
                url = "https://pbs.twimg.com/media/fixture-2.jpg",
                previewUrl = "https://pbs.twimg.com/media/fixture-2.jpg",
            ),
        ),
    )
}
