package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.data.TimelineCache
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.ui.PostDetailController
import dev.nytweetdeck.android.ui.PostDetailDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XApiEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Read-only live verification. Only counts and terminal flags are logged. */
class LiveReplyTerminationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun liveFiniteAndZeroReplyCandidatesStopRequestsAtTheExplicitEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(File(context.noBackupFilesDir, "accounts/accounts.json"))
        val account = store.accountSecrets().first { it.accountId == store.selectedAccountId() }
        val client = XApiEnvironment(context).graphQlClient()
        val timeline = TimelineRepository(client, TimelineResponseParser(), TimelineCache(context.cacheDir.resolve("timelines")))
        val candidates = (timeline.cached(account, "homeForYou")?.posts?.takeIf { it.isNotEmpty() }
            ?: timeline.load(account, "homeForYou", language = "ja").posts)
            .sortedByDescending { it.id.toLongOrNull() ?: 0L }
        val calls = AtomicInteger()
        val ends = AtomicInteger()
        val executor = GraphQlExecutor { credentials, purpose, variables, language ->
            calls.incrementAndGet()
            client.execute(credentials, purpose, variables, language).also { body ->
                if (purpose == "conversation") {
                    val terminated = hasBottomTermination(Json.parseToJsonElement(body))
                    if (terminated) ends.incrementAndGet()
                    println("REPLY_TERMINATION terminal=$terminated replies=${TimelineResponseParser().parseInResponseOrder(body).posts.size} calls=${calls.get()}")
                }
            }
        }
        val state = MutableStateFlow(DeckUiState(selectedAccountId = account.accountId))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PostDetailController(PostDetailRepository(executor), scope, Dispatchers.IO, { account }, state)
        try {
            showDetail(state, controller)
            var finiteVerified = false
            var zeroVerified = false
            // Cached counters may be stale. Inspect a bounded number of newest candidates,
            // and classify the actual conversation result rather than trusting those counters.
            val samples = (candidates.filter { it.replyCount in 1L..20L }.take(2) +
                candidates.filter { it.replyCount == 0L }.take(4)).distinctBy { it.id }
            assertTrue("No cached conversation candidates", samples.isNotEmpty())
            for (post in samples) {
                if (finiteVerified && zeroVerified) break
                composeRule.runOnIdle {
                    controller.reset()
                    controller.open(post.id, post)
                }
                awaitLoaded(state)
                val page = state.value.postDetail.page!!
                println("REPLY_RESULT replies=${page.replies.size} terminal=${page.nextCursor == null} calls=${calls.get()}")
                if (page.nextCursor != null) continue
                assertTerminalUiAndStableRequests(state, controller, calls)
                if (page.replies.isEmpty()) zeroVerified = true else finiteVerified = true
            }
            assertTrue("No finite live conversation reached its terminal page", finiteVerified)
            assertTrue("No explicit Bottom termination was observed", ends.get() > 0)
            println("REPLY_LIVE finite=$finiteVerified zero=$zeroVerified calls=${calls.get()}")
            if (!zeroVerified) {
                println("REPLY_LIVE_ZERO_UNAVAILABLE: bounded live candidates had replies; empty observed response structure is covered separately")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun emptyObservedResponseStructureStopsControllerAndUi() {
        // Structure observed on the device after the last reply page. All values are
        // synthetic; only the cursor + explicit Bottom termination shape is retained.
        val response = """{"data":{"threaded_conversation_with_injections_v2":{"instructions":[
            {"type":"TimelineAddEntries","entries":[{"entryId":"cursor-bottom-0","content":{
                "entryType":"TimelineTimelineCursor","cursorType":"Bottom","value":"terminal-cursor"}}]},
            {"type":"TimelineTerminateTimeline","direction":"Bottom"}
        ]}}}"""
        val calls = AtomicInteger()
        val executor = GraphQlExecutor { _, _, _, _ -> calls.incrementAndGet(); response }
        val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val account = AccountSecrets("7", "7", "user", "User", "bearer", "auth", "csrf", "profile")
        val controller = PostDetailController(PostDetailRepository(executor), scope, Dispatchers.IO, { account }, state)
        val focal = TimelineResponseParser().parse(
            """{"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"123","legacy":{"full_text":"Post"}}}}}""",
        ).posts.single()
        try {
            showDetail(state, controller)
            composeRule.runOnIdle { controller.open(focal.id, focal) }
            awaitLoaded(state)
            assertTrue(state.value.postDetail.page!!.replies.isEmpty())
            assertTerminalUiAndStableRequests(state, controller, calls)
            assertEquals(1, calls.get())
            assertTrue(composeRule.onAllNodesWithTag("empty-replies").fetchSemanticsNodes().isNotEmpty())
            println("REPLY_REPLAY replies=0 terminal=true calls=${calls.get()}")
        } finally {
            scope.cancel()
        }
    }

    private fun awaitLoaded(state: MutableStateFlow<DeckUiState>) {
        composeRule.waitUntil(60_000) {
            val detail = state.value.postDetail
            !detail.isLoadingMore && detail.status != PostDetailStatus.LOADING
        }
        assertTrue("Conversation load failed", state.value.postDetail.status == PostDetailStatus.READY)
        assertTrue("Conversation reply load failed", !state.value.postDetail.loadMoreFailed)
    }

    private fun assertTerminalUiAndStableRequests(
        state: MutableStateFlow<DeckUiState>,
        controller: PostDetailController,
        calls: AtomicInteger,
    ) {
        assertTrue(state.value.postDetail.page!!.nextCursor == null)
        val requestCount = calls.get()
        composeRule.runOnIdle {
            if (!state.value.postDetail.showDeemphasizedReplies) controller.toggleDeemphasizedReplies()
        }
        val page = state.value.postDetail.page!!
        val lastIndex = page.contextPosts.size + 1 + page.replies.size +
            if (page.replies.any { it.quality.isDeemphasized }) 1 else 0
        composeRule.onNodeWithTag("post-detail-replies").performScrollToIndex(lastIndex)
        repeat(3) {
            composeRule.runOnIdle { controller.loadMore() }
            composeRule.mainClock.advanceTimeBy(500)
            composeRule.waitForIdle()
        }
        assertEquals("Terminal conversation requested another page", requestCount, calls.get())
        assertTrue("Loading footer remains at the terminal page", composeRule.onAllNodesWithTag("load-more-replies").fetchSemanticsNodes().isEmpty())
        assertTrue(!state.value.postDetail.isLoadingMore)
    }

    private fun showDetail(state: MutableStateFlow<DeckUiState>, controller: PostDetailController) {
        composeRule.activity.setContent {
            val deck by state.collectAsState()
            NyTweetDeckTheme {
                PostDetailDialog(
                    state = deck.postDetail,
                    replySort = RankingMode.RELEVANCE,
                    onDismiss = controller::close,
                    onRetry = controller::reload,
                    onLoadMore = controller::loadMore,
                    onReplySortChange = {},
                    onToggleDeemphasized = controller::toggleDeemphasizedReplies,
                    onPostClick = {},
                    onQuoteClick = {},
                    onReplyClick = {},
                    onRepostClick = {},
                    onLikeClick = {},
                    onImpressionClick = {},
                    onBookmarkClick = {},
                    onShareClick = {},
                    onDownloadClick = {},
                    autoTranslatePosts = false,
                    mediaPreview = false,
                )
            }
        }
    }

    private fun hasBottomTermination(node: JsonElement): Boolean = when (node) {
        is JsonArray -> node.any(::hasBottomTermination)
        is JsonObject -> ((node["type"] as? JsonPrimitive)?.contentOrNull == "TimelineTerminateTimeline" &&
            (node["direction"] as? JsonPrimitive)?.contentOrNull == "Bottom") || node.values.any(::hasBottomTermination)
        else -> false
    }
}
