package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostActionRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.model.UserProfileUiState
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostActionControllerTest {
    @Test
    fun rejectedLikeAndRepostKeepOptimisticStateUntilThirtySeconds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val state = profileState()
            val controller = PostActionController(
                PostActionRepository(GraphQlExecutor { _, _, _, _ -> error("rejected") }),
                backgroundScope, dispatcher, { account }, state, { testScheduler.currentTime },
            )
            controller.toggle("123", PostActionType.LIKE)
            controller.toggle("123", PostActionType.REPOST)
            assertTrue(state.value.userProfile.posts.single().liked)
            assertTrue(state.value.userProfile.posts.single().reposted)
            runCurrent()
            advanceTimeBy(29_999)
            assertTrue(state.value.userProfile.posts.single().liked)
            assertTrue(state.value.failedPostActions.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertFalse(state.value.userProfile.posts.single().liked)
            assertFalse(state.value.userProfile.posts.single().reposted)
            assertEquals(setOf(PostActionType.LIKE, PostActionType.REPOST), state.value.failedPostActions["123"])
            assertTrue(state.value.pendingPostActions.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun laterTapCannotBeRolledBackByAnOldRejection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val state = profileState()
            var requests = 0
            val controller = PostActionController(
                PostActionRepository(GraphQlExecutor { _, _, _, _ ->
                    requests++
                    if (requests == 1) error("rejected")
                    "{}"
                }), backgroundScope, dispatcher, { account }, state, { testScheduler.currentTime },
            )
            controller.toggle("123", PostActionType.LIKE)
            runCurrent()
            advanceTimeBy(10_000)
            controller.toggle("123", PostActionType.LIKE)
            controller.toggle("123", PostActionType.LIKE)
            runCurrent()
            assertEquals(2, requests)
            assertTrue(state.value.pendingPostActions.isEmpty())
            advanceTimeBy(20_000)
            runCurrent()
            assertTrue(state.value.userProfile.posts.single().liked)
            assertEquals(2, requests)
            assertTrue(state.value.failedPostActions.isEmpty())
            assertTrue(state.value.pendingPostActions.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun bookmarkRejectionRetainsImmediateRollback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val state = profileState()
            val controller = PostActionController(
                PostActionRepository(GraphQlExecutor { _, _, _, _ -> error("rejected") }),
                backgroundScope, dispatcher, { account }, state, { testScheduler.currentTime },
            )
            controller.toggle("123", PostActionType.BOOKMARK)
            assertTrue(state.value.userProfile.posts.single().bookmarked)
            runCurrent()
            assertFalse(state.value.userProfile.posts.single().bookmarked)
            assertTrue(state.value.pendingPostActions.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun responseLatencyCountsTowardThirtySecondRollbackDeadline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val state = profileState()
            var responseLatency = 0L
            val controller = PostActionController(
                PostActionRepository(GraphQlExecutor { _, _, _, _ ->
                    responseLatency = 20_000L
                    error("rejected after latency")
                }), backgroundScope, dispatcher, { account }, state,
                { testScheduler.currentTime + responseLatency },
            )
            controller.toggle("123", PostActionType.LIKE)
            runCurrent()
            advanceTimeBy(9_999)
            assertTrue(state.value.userProfile.posts.single().liked)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(state.value.userProfile.posts.single().liked)
        } finally { Dispatchers.resetMain() }
    }

    private fun profileState() = MutableStateFlow(DeckUiState(
        selectedAccountId = "7",
        userProfile = UserProfileUiState(posts = TimelineResponseParser().parse(
            """{"tweet":{"rest_id":"123","legacy":{"full_text":"fixture","lang":"en","favorite_count":0},"core":{"user_results":{"result":{"rest_id":"7","legacy":{"screen_name":"user","name":"User"}}}}}}""",
        ).posts),
    ))

    private val account = AccountSecrets("7", "7", "user", "User", "bearer", "auth", "csrf", "profile")
}
