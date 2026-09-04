package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.ListMembershipAction
import dev.nytweetdeck.android.data.ListMembershipExecutor
import dev.nytweetdeck.android.data.UserAction
import dev.nytweetdeck.android.data.UserActionExecutor
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.ColumnTimelineState
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.TimelineLoadStatus
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class PostMenuControllerTest {
    @Test
    fun differentUserActionsRunIndependentlyAndKeepPendingUntilBothFinish() {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + main)
        try {
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val actions = Collections.synchronizedList(mutableListOf<UserAction>())
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = PostMenuController(
                UserActionExecutor { _, _, action, _ ->
                    actions += action
                    started.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                },
                null,
                scope,
                io,
                { account() },
                state,
            )

            runBlocking(main) {
                controller.userAction(post(), UserAction.FOLLOW)
                controller.userAction(post(), UserAction.MUTE)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(state.value.postMenuActionPending)
            release.countDown()
            await { !state.value.postMenuActionPending }

            assertEquals(setOf(UserAction.FOLLOW, UserAction.MUTE), actions.toSet())
            assertFalse(state.value.postMenuActionFailed)
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
            io.close()
        }
    }

    @Test
    fun confirmedMuteAndBlockRemoveEveryPostFromThatUserAcrossTimelines() {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + main)
        try {
            val mutedOriginal = post()
            val mutedRepost = post().copy(
                id = "2",
                author = Author("99", "other", "Other", null, false),
                repostedBy = mutedOriginal.author,
            )
            val other = post().copy(
                id = "3",
                author = Author("99", "other", "Other", null, false),
            )
            val initial = DeckUiState(
                selectedAccountId = "7",
                timelines = mapOf(
                    "home" to ColumnTimelineState(
                        status = TimelineLoadStatus.READY,
                        posts = listOf(mutedOriginal, mutedRepost, other),
                    ),
                    "search" to ColumnTimelineState(
                        status = TimelineLoadStatus.READY,
                        posts = listOf(mutedOriginal, other),
                    ),
                ),
            )
            val state = MutableStateFlow(initial)
            val controller = PostMenuController(
                UserActionExecutor { _, _, _, _ -> },
                null,
                scope,
                io,
                { account() },
                state,
            )

            listOf(UserAction.MUTE, UserAction.BLOCK).forEach { action ->
                state.value = initial
                runBlocking(main) { controller.userAction(mutedOriginal, action) }
                await { state.value.timelines.values.all { timeline -> timeline.posts.size == 1 } }
                assertEquals(setOf("3"), state.value.timelines["home"]?.posts?.map(Post::id)?.toSet())
                assertEquals(setOf("3"), state.value.timelines["search"]?.posts?.map(Post::id)?.toSet())
            }
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
            io.close()
        }
    }

    @Test
    fun failedMuteKeepsTheUsersPostsVisible() {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + main)
        try {
            val state = MutableStateFlow(
                DeckUiState(
                    selectedAccountId = "7",
                    timelines = mapOf(
                        "home" to ColumnTimelineState(
                            status = TimelineLoadStatus.READY,
                            posts = listOf(post()),
                        ),
                    ),
                ),
            )
            val controller = PostMenuController(
                UserActionExecutor { _, _, _, _ -> error("network") },
                null,
                scope,
                io,
                { account() },
                state,
            )

            runBlocking(main) { controller.userAction(post(), UserAction.MUTE) }
            await { state.value.postMenuActionFailed }

            assertEquals(listOf("1"), state.value.timelines["home"]?.posts?.map(Post::id))
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
            io.close()
        }
    }

    @Test
    fun listMembershipActionsKeepSubmissionOrderForTheSameList() {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + main)
        try {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val actions = Collections.synchronizedList(mutableListOf<ListMembershipAction>())
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val executor = ListMembershipExecutor { _, _, _, action, _ ->
                actions += action
                if (action == ListMembershipAction.ADD) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
            }
            val controller = PostMenuController(
                null, executor, scope, io, { account() }, state,
            )

            runBlocking(main) { controller.listMembership(post(), "99", add = true) }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            runBlocking(main) { controller.listMembership(post(), "99", add = false) }
            releaseFirst.countDown()
            await { actions.size == 2 && !state.value.postMenuActionPending }

            assertEquals(
                listOf(ListMembershipAction.ADD, ListMembershipAction.REMOVE),
                actions.toList(),
            )
            assertFalse(state.value.postMenuActionFailed)
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
            io.close()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private fun account() = AccountSecrets(
        "7", "7", "fixture", "Fixture", "bearer", "auth", "csrf", "profile",
    )

    private fun post() = Post(
        id = "1",
        text = "fixture",
        language = "en",
        createdAt = null,
        author = Author("42", "author", "Author", null, false),
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
        media = emptyList(),
    )
}
