package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.ui.PostDetailController
import dev.nytweetdeck.android.ui.PostDetailDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class RecursiveReplyNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun replyBodyTapsTraverseThreeLevelsAndBackRestoresEachConversation() {
        val account = AccountSecrets("7", "7", "user", "User", "bearer", "auth", "csrf", "profile")
        val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val executor = GraphQlExecutor { _, purpose, variables, _ ->
            check(purpose == "conversation")
            val focal = variables.getValue("focalTweetId").toString().toInt()
            val tweets = (123..minOf(focal + 1, 126)).joinToString(",") { tweet(it) }
            """{"tweets":[$tweets],"instructions":[{"type":"TimelineTerminateTimeline","direction":"Bottom"}]}"""
        }
        val controller = PostDetailController(PostDetailRepository(executor), scope, Dispatchers.IO, { account }, state)
        var replyActions = 0
        var likeActions = 0
        fun open(id: String) {
            val page = state.value.postDetail.page
            val known = page?.replies?.firstOrNull { it.post.id == id }?.post
                ?: page?.contextPosts?.firstOrNull { it.id == id }
                ?: page?.post?.takeIf { it.id == id }
            controller.open(id, known)
        }
        try {
            composeRule.activity.setContent {
                val deck by state.collectAsState()
                NyTweetDeckTheme {
                    PostDetailDialog(
                        state = deck.postDetail, replySort = RankingMode.RELEVANCE,
                        onDismiss = controller::close, onRetry = controller::reload,
                        onLoadMore = controller::loadMore, onReplySortChange = {},
                        onToggleDeemphasized = controller::toggleDeemphasizedReplies,
                        onPostClick = ::open, onQuoteClick = ::open,
                        onReplyClick = { replyActions++ }, onLikeClick = { likeActions++ },
                        onRepostClick = {}, onImpressionClick = {}, onBookmarkClick = {},
                        onShareClick = {}, onDownloadClick = {}, autoTranslatePosts = false,
                    )
                }
            }
            val focal = TimelineResponseParser().parse("""{"tweet":${tweet(123)}}""").posts.single()
            composeRule.runOnIdle { controller.open("123", focal) }
            awaitPage("123", state)
            for (id in 124..126) {
                composeRule.onNodeWithTag("post-detail-replies").performScrollToKey(id.toString())
                // Real pointer input on the body, rather than invoking the card semantics.
                composeRule.onNodeWithTag("post-body-$id", useUnmergedTree = true).performTouchInput { click() }
                awaitPage(id.toString(), state)
            }
            composeRule.onNodeWithTag("post-detail-replies").performScrollToKey("detail-post")
            composeRule.onNodeWithTag("post-action-reply-126").performClick()
            composeRule.onNodeWithTag("post-action-like-126").performClick()
            assertEquals(1, replyActions)
            assertEquals(1, likeActions)
            assertEquals("126", state.value.postDetail.postId)
            for (id in 125 downTo 123) {
                composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
                awaitPage(id.toString(), state)
            }
            composeRule.onNodeWithTag("close-post-detail").performClick()
            composeRule.waitForIdle()
            assertEquals(PostDetailStatus.CLOSED, state.value.postDetail.status)
        } finally {
            scope.cancel()
        }
    }

    private fun awaitPage(id: String, state: MutableStateFlow<DeckUiState>) {
        composeRule.waitUntil(10_000) {
            val detail = state.value.postDetail
            detail.postId == id && detail.status == PostDetailStatus.READY && !detail.isLoadingMore
        }
        assertFalse(state.value.postDetail.loadMoreFailed)
        assertEquals(id, state.value.postDetail.page?.post?.id)
    }

    private fun tweet(id: Int): String {
        val parent = if (id == 123) "null" else "\"${id - 1}\""
        return """{"__typename":"Tweet","rest_id":"$id","legacy":{"full_text":"Reply body $id","in_reply_to_status_id_str":$parent}}"""
    }
}
