package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
    fun videoViewerUsesTheInlineControlsFullscreenAndAutoHidesOnAquos() {
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
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(
                "inline-video-connected",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("inline-video-video-1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-play", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-seek", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-volume", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("inline-video-fullscreen", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "inline-video-fullscreen",
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("media-viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("media-video").assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(
                "media-video-connected",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media-video-play", useUnmergedTree = true)
            .assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("media-video-mute", useUnmergedTree = true)
            .assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("media-video-seek", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("media-video-volume", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("media-video-loop", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("media-video-fullscreen", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("media-video-rotate", useUnmergedTree = true).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(3_100)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("media-video-controls", useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithTag("media-video-surface", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("media-video-controls", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
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

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(
                "inline-video-connected",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(
            "inline-video-autoplay-video",
            useUnmergedTree = true,
        ).assertIsDisplayed()
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
