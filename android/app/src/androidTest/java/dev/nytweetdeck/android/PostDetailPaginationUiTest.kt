package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.ConversationReply
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostDetailPage
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.model.PostDetailUiState
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.ui.PostDetailDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PostDetailPaginationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun relatedPostHasSeparateHeadingAndOpensWithoutReplyThreadDecoration() {
        val initial = detail(cursor = null)
        val state = mutableStateOf(initial.copy(page = initial.page!!.copy(relatedPosts = listOf(post("801")))))
        var opened: String? = null
        showDetail(state, onLoadMore = {}, onPostClick = { opened = it })
        composeRule.onNodeWithTag("empty-replies").assertIsDisplayed()
        composeRule.onNodeWithTag("post-detail-replies").performScrollToKey("related-posts-heading")
        composeRule.onNodeWithTag("related-posts-heading").assertIsDisplayed()
        composeRule.onNodeWithTag("post-detail-replies").performScrollToKey("related-801")
        composeRule.onNodeWithTag("reply-thread-content-801").assertDoesNotExist()
        composeRule.onNodeWithTag("post-801").performClick()
        assertEquals("801", opened)
        composeRule.onNodeWithTag("load-more-replies").assertDoesNotExist()
    }

    @Test
    fun failedAutomaticPageWaitsForExplicitRetryThenRemovesFooterAtEnd() {
        val state = mutableStateOf(detail(cursor = "next"))
        var requests = 0
        showDetail(state, onLoadMore = {
            requests++
            state.value = if (requests == 1) {
                state.value.copy(loadMoreFailed = true)
            } else {
                detail(cursor = null, replies = listOf(ConversationReply(post("201"))))
            }
        })

        composeRule.waitForIdle()
        assertEquals(1, requests)
        composeRule.onNodeWithTag("load-more-replies").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(2, requests)
        composeRule.onNodeWithTag("load-more-replies").assertDoesNotExist()
        composeRule.onNodeWithTag("reply-thread-content-201").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun initialReplyFailureUsesInitialRetryAndDoesNotClaimRepliesAreEmpty() {
        val state = mutableStateOf(detail(cursor = null).copy(loadMoreFailed = true))
        var initialRequests = 0
        var additionalRequests = 0
        showDetail(state, onLoadMore = { additionalRequests++ }, onRetry = {
            initialRequests++
            state.value = detail(cursor = null)
        })

        composeRule.onNodeWithTag("empty-replies").assertDoesNotExist()
        composeRule.onNodeWithTag("load-more-replies").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, initialRequests)
        assertEquals(0, additionalRequests)
        composeRule.onNodeWithTag("empty-replies").assertIsDisplayed()
        composeRule.onNodeWithTag("load-more-replies").assertDoesNotExist()
    }

    @Test
    fun emptyInitialResponseStopsLoadingAndCanBeClosed() {
        val state = mutableStateOf(detail(cursor = null).copy(isLoadingMore = true))
        var additionalRequests = 0
        showDetail(state, onLoadMore = { additionalRequests++ })

        composeRule.onNodeWithTag("load-more-replies").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("empty-replies").assertDoesNotExist()
        composeRule.runOnIdle { state.value = detail(cursor = null) }
        composeRule.onNodeWithTag("empty-replies").assertIsDisplayed()
        composeRule.onNodeWithTag("load-more-replies").assertDoesNotExist()
        assertEquals(0, additionalRequests)
        composeRule.onNodeWithTag("close-post-detail").performClick()
        composeRule.onNodeWithTag("post-detail").assertDoesNotExist()
    }

    @Test
    fun populatedTerminalPageRemainsAtEndAfterScrollingThroughReplies() {
        val replies = (201..208).map { ConversationReply(post(it.toString())) }
        val state = mutableStateOf(detail(cursor = null, replies = replies))
        var additionalRequests = 0
        showDetail(state, onLoadMore = { additionalRequests++ })

        composeRule.onNodeWithTag("post-detail-replies").performScrollToKey("208")
        composeRule.onNodeWithTag("reply-thread-content-208").assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("load-more-replies").assertDoesNotExist()
        composeRule.onNodeWithTag("empty-replies").assertDoesNotExist()
        assertEquals(0, additionalRequests)
    }

    @Test
    fun sortChangeCanResumeLoadingAfterFailureWithoutAutomaticErrorRetries() {
        val state = mutableStateOf(detail(cursor = "next").copy(loadMoreFailed = true))
        var additionalRequests = 0
        var chosenSort: RankingMode? = null
        showDetail(state, onLoadMore = { additionalRequests++ }, onSortChange = { mode ->
            chosenSort = mode
            state.value = detail(cursor = null).copy(isLoadingMore = true)
        })

        composeRule.onNodeWithTag("reply-sort-recency").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(RankingMode.RECENCY, chosenSort)
        assertEquals(0, additionalRequests)
        composeRule.onNodeWithTag("load-more-replies").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state.value = detail(cursor = null) }
        composeRule.onNodeWithTag("load-more-replies").assertDoesNotExist()
    }

    private fun showDetail(
        state: MutableState<PostDetailUiState>,
        onLoadMore: () -> Unit,
        onRetry: () -> Unit = {},
        onSortChange: (RankingMode) -> Unit = {},
        onPostClick: (String) -> Unit = {},
    ) {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                PostDetailDialog(
                    state = state.value,
                    replySort = RankingMode.RELEVANCE,
                    onDismiss = { state.value = PostDetailUiState() },
                    onRetry = onRetry,
                    onLoadMore = onLoadMore,
                    onReplySortChange = onSortChange,
                    onToggleDeemphasized = {},
                    onPostClick = onPostClick,
                    onQuoteClick = {},
                    onReplyClick = {},
                    onRepostClick = {},
                    onLikeClick = {},
                    onImpressionClick = {},
                    onBookmarkClick = {},
                    onShareClick = {},
                    onDownloadClick = {},
                    autoTranslatePosts = false,
                )
            }
        }
    }

    private fun detail(cursor: String?, replies: List<ConversationReply> = emptyList()) = PostDetailUiState(
        status = PostDetailStatus.READY,
        postId = "123",
        page = PostDetailPage(post("123"), replies, cursor, RankingMode.RELEVANCE),
    )

    private fun post(id: String) = Post(
        id, "reply", "en", null, Author(id, "user$id", "User", null, false),
        null, null, 0, 0, 0, 0, 0, 0, false, false, false,
        if (id == "123") null else "123", null, null, null, null, null, null, emptyList(),
    )
}
