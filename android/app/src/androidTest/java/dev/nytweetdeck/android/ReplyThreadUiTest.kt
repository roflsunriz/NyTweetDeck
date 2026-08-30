package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.ConversationReply
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostDetailPage
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.model.PostDetailUiState
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.ui.PostDetailDialog
import dev.nytweetdeck.android.ui.ReplyThreadContainer
import dev.nytweetdeck.android.ui.buildReplyThreadLayout
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReplyThreadUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nestedRepliesExposeLinesAndIncreasingIndentOnANarrowScreen() {
        showThread(LayoutDirection.Ltr)

        listOf("B", "E", "F").forEach { id ->
            composeRule.onNodeWithTag("reply-thread-connectors-$id").assertIsDisplayed()
            composeRule.onNodeWithTag("reply-thread-content-$id").assertIsDisplayed()
        }
        val b = bounds("reply-thread-content-B")
        val e = bounds("reply-thread-content-E")
        val f = bounds("reply-thread-content-F")
        assertTrue(b.left < e.left && e.left < f.left)
        assertTrue(f.width > 200f)
    }

    @Test
    fun rtlMirrorsIndentAndStillLeavesTheReplyBodyUsable() {
        showThread(LayoutDirection.Rtl)

        val b = bounds("reply-thread-content-B")
        val e = bounds("reply-thread-content-E")
        val f = bounds("reply-thread-content-F")
        assertTrue(b.right > e.right && e.right > f.right)
        assertTrue(f.width > 200f)
    }

    @Test
    fun postDetailConnectsThreadAnnotationsToRenderedReplies() {
        val focal = reply("A", "root").post.copy(replyToPostId = null)
        val replies = listOf(reply("B", "A"), reply("E", "B"))
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                PostDetailDialog(
                    state = PostDetailUiState(
                        status = PostDetailStatus.READY,
                        postId = "A",
                        page = PostDetailPage(focal, replies, null, RankingMode.RELEVANCE),
                    ),
                    replySort = RankingMode.RELEVANCE,
                    onDismiss = {},
                    onRetry = {},
                    onLoadMore = {},
                    onReplySortChange = {},
                    onToggleDeemphasized = {},
                    onPostClick = {},
                    onQuoteClick = {},
                    onReplyClick = {},
                    onRepostClick = {},
                    onLikeClick = {},
                    onImpressionClick = {},
                    onBookmarkClick = {},
                    onShareClick = {},
                    onDownloadClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("reply-thread-connectors-B").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reply-thread-connectors-E").performScrollTo().assertIsDisplayed()
    }

    private fun showThread(layoutDirection: LayoutDirection) {
        val positions = buildReplyThreadLayout(
            "A",
            listOf(reply("B", "A"), reply("E", "B"), reply("F", "E")),
        )
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Column(Modifier.width(320.dp)) {
                        positions.forEach { position ->
                            ReplyThreadContainer(position) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(72.dp)
                                        .testTag("reply-body-${position.reply.post.id}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun reply(id: String, parentId: String) = ConversationReply(
        Post(
            id, "reply", "en", null, Author(id, "user$id", "User", null, false),
            null, null, 0, 0, 0, 0, 0, 0, false, false, false,
            parentId, null, null, null, null, null, null, emptyList(),
        ),
    )
}
