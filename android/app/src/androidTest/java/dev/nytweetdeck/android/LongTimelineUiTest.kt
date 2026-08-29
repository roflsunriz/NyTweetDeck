package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.ui.PostCard
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Rule
import org.junit.Test

class LongTimelineUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun thousandPostTimelineRemainsVirtualizedAndScrollableOnAquos() {
        val posts = (0 until 1_000).map(::post)
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                LazyColumn(Modifier.testTag("long-timeline")) {
                    items(posts, key = Post::id) { item -> PostCard(post = item) }
                }
            }
        }

        composeRule.onNodeWithTag("long-timeline").performScrollToIndex(999)
        composeRule.onNodeWithTag("post-999").assertIsDisplayed()
        composeRule.onNodeWithTag("long-timeline").performScrollToIndex(0)
        composeRule.onNodeWithTag("post-0").assertIsDisplayed()
    }

    private fun post(index: Int) = Post(
        id = index.toString(),
        text = "Long timeline fixture $index",
        language = "en",
        createdAt = "2026-08-29T00:00:00Z",
        author = Author("7", "fixture", "Fixture", null, false),
        repostedBy = null,
        conversationSection = null,
        replyCount = index.toLong(),
        repostCount = index.toLong(),
        quoteCount = 0,
        likeCount = index.toLong(),
        bookmarkCount = 0,
        viewCount = index.toLong(),
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
    )
}
