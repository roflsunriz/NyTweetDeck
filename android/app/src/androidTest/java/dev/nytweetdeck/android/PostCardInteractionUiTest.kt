package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.EmbeddedPost
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.ui.PostCard
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PostCardInteractionUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private var openedPost: String? = null
    private var openedQuote: String? = null

    @Before
    fun showQuotedRepost() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PostCard(
                        post = fixturePost(),
                        onPostClick = { openedPost = it },
                        onQuoteClick = { openedQuote = it },
                    )
                }
            }
        }
    }

    @Test
    fun quoteAndParentHaveIndependentTargetsAndAllActionsRemainVisible() {
        composeRule.onNodeWithTag("post-body-101", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("post-quote-101-99", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertEquals("99", openedQuote)
        assertNull(openedPost)

        listOf("reply", "repost", "like", "impressions", "bookmark", "share", "download")
            .forEach { action ->
                composeRule.onNodeWithTag("post-action-$action-101").assertExists()
            }
    }

    private fun fixturePost(): Post {
        val author = Author("7", "author", "投稿者", null, true)
        return Post(
            id = "101",
            text = "改行を含む本文\n#NyTweetDeck",
            language = "ja",
            createdAt = "2026-08-29T00:00:00Z",
            author = author,
            repostedBy = Author("8", "reposter", "リポストした人", null, false),
            conversationSection = null,
            replyCount = 1,
            repostCount = 2,
            quoteCount = 3,
            likeCount = 4,
            bookmarkCount = 5,
            viewCount = 6,
            liked = true,
            reposted = true,
            bookmarked = true,
            replyToPostId = "90",
            replyToUsername = "parent",
            quotedPostId = "99",
            quotedPost = EmbeddedPost(
                "99",
                "引用本文",
                "ja",
                "2026-08-29T00:00:00Z",
                Author("9", "quoted", "引用元", null, false),
                null,
                null,
                emptyList(),
            ),
            communityNote = null,
            preTranslated = null,
            article = null,
            media = emptyList(),
        )
    }
}
