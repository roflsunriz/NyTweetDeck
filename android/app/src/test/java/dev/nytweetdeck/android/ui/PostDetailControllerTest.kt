package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.PostDetailStatus
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailControllerTest {
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
