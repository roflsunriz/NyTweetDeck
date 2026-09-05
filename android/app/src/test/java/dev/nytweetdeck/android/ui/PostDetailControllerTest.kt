package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.PostDetailStatus
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailControllerTest {
    @Test
    fun paginationMergesRelatedSeparatelyAndPromotesDuplicateToReply() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val executor = GraphQlExecutor { _, _, variables, _ ->
                val additional = variables["cursor"] != null
                val reply = if (additional) "801" else "201"
                val related = if (additional) "802" else "801"
                """{"entries":[
                    {"entryId":"reply-$reply","content":{"tweet_results":{"result":${tweet(reply, "reply")}}}},
                    {"entryId":"tweetdetailrelatedtweets-123","content":{"items":[
                        {"item":{"tweet_results":{"result":${tweet(related, "related")}}}},
                        {"item":{"tweet_results":{"result":${tweet("801", "duplicate")}}}}
                    ]}},
                    {"entryId":"cursor-bottom","content":{"value":"${if (additional) "" else "next"}"}}
                ]}"""
            }
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = PostDetailController(PostDetailRepository(executor), this, dispatcher, { account() }, state)
            controller.open("123", TimelineResponseParser().parse(detailResponse()).posts.single())
            advanceUntilIdle()
            assertEquals(listOf("201"), state.value.postDetail.page?.replies?.map { it.post.id })
            assertEquals(listOf("801"), state.value.postDetail.page?.relatedPosts?.map { it.id })
            controller.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("201", "801"), state.value.postDetail.page?.replies?.map { it.post.id })
            assertEquals(listOf("802"), state.value.postDetail.page?.relatedPosts?.map { it.id })
            assertNull(state.value.postDetail.page?.nextCursor)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun knownFocalPostIsShownImmediatelyAndIsNotRefetched() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val calls = mutableListOf<String>()
            val executor = GraphQlExecutor { _, purpose, _, _ ->
                calls += purpose
                when (purpose) {
                    "conversation" -> conversationResponse("201", "next-cursor")
                    else -> error("unexpected purpose")
                }
            }
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = PostDetailController(
                PostDetailRepository(executor),
                this,
                dispatcher,
                { account() },
                state,
            )
            val knownPost = TimelineResponseParser().parse(detailResponse()).posts.single()

            controller.open("123", knownPost)

            assertEquals(PostDetailStatus.READY, state.value.postDetail.status)
            assertEquals("123", state.value.postDetail.page?.post?.id)
            assertEquals(true, state.value.postDetail.isLoadingMore)
            assertEquals(emptyList<String>(), calls)

            advanceUntilIdle()

            assertEquals(listOf("conversation"), calls)
            assertEquals(listOf("201"), state.value.postDetail.page?.replies?.map { it.post.id })
            assertFalse(state.value.postDetail.isLoadingMore)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun repeatedReplyCursorStopsAutomaticPaginationWithoutRefetchingTheFocalPost() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val calls = mutableListOf<String>()
            val executor = GraphQlExecutor { _, purpose, variables, _ ->
                calls += purpose
                when (purpose) {
                    "postDetail" -> detailResponse()
                    "conversation" -> conversationResponse(
                        replyId = if (variables["cursor"] == null) "201" else "202",
                        nextCursor = "same-cursor",
                    )
                    else -> error("unexpected purpose")
                }
            }
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = PostDetailController(
                PostDetailRepository(executor),
                this,
                dispatcher,
                { account() },
                state,
            )

            controller.open("123")
            advanceUntilIdle()
            assertEquals(listOf("postDetail", "conversation"), calls)

            controller.loadMore()
            advanceUntilIdle()

            assertEquals(listOf("postDetail", "conversation", "conversation"), calls)
            assertEquals(listOf("201", "202"), state.value.postDetail.page?.replies?.map { it.post.id })
            assertNull(state.value.postDetail.page?.nextCursor)
            assertFalse(state.value.postDetail.isLoadingMore)

            controller.loadMore()
            advanceUntilIdle()
            assertEquals(3, calls.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedAdditionalPageKeepsItsCursorAndCanBeRetried() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var attempts = 0
            val executor = GraphQlExecutor { _, _, variables, _ ->
                if (variables["cursor"] == null) conversationResponse("201", "next") else {
                    attempts++
                    if (attempts == 1) error("temporary failure")
                    conversationResponse("202", "next")
                }
            }
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = PostDetailController(PostDetailRepository(executor), this, dispatcher, { account() }, state)
            controller.open("123", TimelineResponseParser().parse(detailResponse()).posts.single())
            advanceUntilIdle()
            controller.loadMore()
            advanceUntilIdle()
            assertTrue(state.value.postDetail.loadMoreFailed)
            assertFalse(state.value.postDetail.isLoadingMore)
            assertEquals("next", state.value.postDetail.page?.nextCursor)
            assertEquals(1, attempts)

            controller.loadMore()
            advanceUntilIdle()
            assertEquals(2, attempts)
            assertFalse(state.value.postDetail.loadMoreFailed)
            assertEquals(listOf("201", "202"), state.value.postDetail.page?.replies?.map { it.post.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun emptyAdditionalPageWithExplicitEndStopsPagination() = runTest {
        assertTerminalPageEndsPagination("""{"instructions":[{"type":"TimelineTerminateTimeline","direction":"Bottom"}],"entries":[
            {"entryId":"cursor-bottom","content":{"cursorType":"Bottom","value":"different"}}
        ]}""")
    }

    @Test
    fun duplicateOnlyAdditionalPageWithExplicitEndStopsPagination() = runTest {
        assertTerminalPageEndsPagination(conversationResponse("201", "different").replaceFirst(
            "{", """{"instructions":[{"type":"TimelineTerminateTimeline","direction":"Bottom"}],""",
        ))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertTerminalPageEndsPagination(response: String) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var calls = 0
            val executor = GraphQlExecutor { _, _, variables, _ ->
                calls++
                if (variables["cursor"] == null) conversationResponse("201", "next") else response
            }
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = PostDetailController(PostDetailRepository(executor), this, dispatcher, { account() }, state)
            controller.open("123", TimelineResponseParser().parse(detailResponse()).posts.single())
            advanceUntilIdle()
            controller.loadMore()
            advanceUntilIdle()
            assertNull(state.value.postDetail.page?.nextCursor)
            assertFalse(state.value.postDetail.isLoadingMore)
            assertFalse(state.value.postDetail.loadMoreFailed)
            assertEquals(listOf("201"), state.value.postDetail.page?.replies?.map { it.post.id })
            controller.loadMore()
            advanceUntilIdle()
            assertEquals(2, calls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun knownPostInitialFailureCanBeReloadedWithoutLosingTheFocalPost() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var attempts = 0
            val executor = GraphQlExecutor { _, purpose, _, _ ->
                assertEquals("conversation", purpose)
                attempts++
                if (attempts == 1) error("temporary failure")
                conversationResponse("201", "next")
            }
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = PostDetailController(PostDetailRepository(executor), this, dispatcher, { account() }, state)
            controller.open("123", TimelineResponseParser().parse(detailResponse()).posts.single())
            advanceUntilIdle()
            assertTrue(state.value.postDetail.loadMoreFailed)
            assertNull(state.value.postDetail.page?.nextCursor)
            assertEquals("123", state.value.postDetail.page?.post?.id)

            controller.reload()
            assertTrue(state.value.postDetail.isLoadingMore)
            advanceUntilIdle()
            assertFalse(state.value.postDetail.loadMoreFailed)
            assertFalse(state.value.postDetail.isLoadingMore)
            assertEquals(listOf("201"), state.value.postDetail.page?.replies?.map { it.post.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun additionalPageCannotLeakIntoAnotherDetailAndBackRestartsInterruptedLoad() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val known = TimelineResponseParser().parse(detailResponse()).posts.single()
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            lateinit var controller: PostDetailController
            val executor = GraphQlExecutor { _, _, variables, _ ->
                when {
                    variables["cursor"] != null -> {
                        controller.open("456", known.copy(id = "456"))
                        conversationResponse("299", "stale")
                    }
                    variables["focalTweetId"] == "456" -> {
                        assertTrue(state.value.postDetail.isLoadingMore)
                        assertTrue(state.value.postDetail.page!!.replies.isEmpty())
                        conversationResponse("457", "other")
                    }
                    else -> conversationResponse("201", "next")
                }
            }
            controller = PostDetailController(PostDetailRepository(executor), this, dispatcher, { account() }, state)
            controller.open("123", known)
            advanceUntilIdle()
            controller.loadMore()
            advanceUntilIdle()
            assertEquals("456", state.value.postDetail.postId)
            assertEquals(listOf("457"), state.value.postDetail.page?.replies?.map { it.post.id })

            controller.close()
            advanceUntilIdle()
            assertEquals("123", state.value.postDetail.postId)
            assertFalse(state.value.postDetail.isLoadingMore)
            assertEquals(listOf("201"), state.value.postDetail.page?.replies?.map { it.post.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sortReloadRejectsThePreviousInitialResultForTheSamePost() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            lateinit var controller: PostDetailController
            var attempts = 0
            val executor = GraphQlExecutor { _, _, variables, _ ->
                attempts++
                if (attempts == 1) {
                    state.value = state.value.copy(replySort = RankingMode.RECENCY)
                    controller.reload()
                    conversationResponse("201", "old")
                } else {
                    assertEquals("Recency", variables["rankingMode"])
                    assertTrue(state.value.postDetail.isLoadingMore)
                    assertTrue(state.value.postDetail.page!!.replies.isEmpty())
                    conversationResponse("202", "new")
                }
            }
            controller = PostDetailController(PostDetailRepository(executor), this, dispatcher, { account() }, state)
            controller.open("123", TimelineResponseParser().parse(detailResponse()).posts.single())
            advanceUntilIdle()
            assertEquals(listOf("202"), state.value.postDetail.page?.replies?.map { it.post.id })
            assertEquals(RankingMode.RECENCY, state.value.postDetail.page?.rankingMode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun closedDetailCannotBeReopenedByAnInFlightResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            lateinit var controller: PostDetailController
            val executor = GraphQlExecutor { _, _, _, _ ->
                controller.close()
                conversationResponse("201", "next")
            }
            controller = PostDetailController(PostDetailRepository(executor), this, dispatcher, { account() }, state)
            controller.open("123", TimelineResponseParser().parse(detailResponse()).posts.single())
            advanceUntilIdle()
            assertEquals(PostDetailStatus.CLOSED, state.value.postDetail.status)
            assertNull(state.value.postDetail.page)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun account() = AccountSecrets(
        "7", "7", "user", "User", "bearer", "auth", "csrf", "profile",
    )

    private fun detailResponse() =
        """{"data":{"tweet":{"result":${tweet("123", "focal")}}}}"""

    private fun conversationResponse(replyId: String, nextCursor: String) =
        """{"entries":[
            {"entryId":"reply-$replyId","content":{"itemContent":{"tweet_results":{"result":${tweet(replyId, "reply")}}}}},
            {"entryId":"cursor-bottom","content":{"cursorType":"Bottom","value":"$nextCursor"}}
        ]}""".trimIndent()

    private fun tweet(id: String, text: String) =
        """{"__typename":"Tweet","rest_id":"$id","legacy":{"full_text":"$text"}}"""
}
