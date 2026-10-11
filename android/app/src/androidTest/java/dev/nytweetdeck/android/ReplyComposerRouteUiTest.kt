package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.*
import dev.nytweetdeck.android.model.*
import dev.nytweetdeck.android.ui.DeckViewModel
import dev.nytweetdeck.android.ui.NyTweetDeckApp
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Uses the complete app layering so a composer hidden behind a route fails the input check. */
class ReplyComposerRouteUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var root: File
    private lateinit var viewModel: DeckViewModel
    private val requests = CopyOnWriteArrayList<String>()
    private val fixtureFailures = CopyOnWriteArrayList<String>()

    @Before
    fun showIsolatedApp() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "reply-route-${System.nanoTime()}").apply { mkdirs() }
        val accountFile = root.resolve("accounts.json")
        AccountStore(accountFile).addOrReplace(
            AccountSecrets("7", "7", "user", "User", "fixture", "fixture", "fixture", "fixture"), true,
        )
        val executor = GraphQlExecutor { _, purpose, variables, _ ->
            requests += purpose
            try {
            val body = when (purpose) {
                "conversation" -> {
                    val id = variables.getValue("focalTweetId").toString().toInt()
                    """{"tweets":[${tweet(id)},${tweet(id + 1)}],"instructions":[{"type":"TimelineTerminateTimeline","direction":"Bottom"}]}"""
                }
                "userByRestId" -> """{"data":{"user":{"result":{"__typename":"User","rest_id":"42","core":{"screen_name":"fixture","name":"Fixture"}}}}}"""
                "userPosts" -> """{"tweet":${tweet(123)}}"""
                "postDetail" -> """{"tweet":${tweet(variables.getValue("tweetId").toString().toInt())}}"""
                "followersYouKnow" -> "{}"
                else -> error("unexpected fixture request $purpose")
            }
            Json.parseToJsonElement(body)
            body
            } catch (failure: Exception) {
                fixtureFailures += "$purpose:${failure.javaClass.simpleName}"
                throw failure
            }
        }
        viewModel = DeckViewModel(
            settingsStore = DeckSettingsStore(root.resolve("layout.json").toPath()),
            accountStoreFile = accountFile,
            postDetailRepository = PostDetailRepository(executor),
            postComposerRepository = PostComposerRepository(executor),
            userDirectoryRepository = UserDirectoryRepository(executor),
        )
        composeRule.activity.setContent { NyTweetDeckApp(providedViewModel = viewModel) }
        awaitState("account initialization") { !viewModel.state.value.isInitializing }
        assertEquals("Fixture account was not selected: ${diagnostics()}", "7", viewModel.state.value.selectedAccountId)
    }

    @After
    fun removeFixture() { if (::root.isInitialized) root.deleteRecursively() }

    @Test
    fun detailAndRecursiveReplyOpenAnEditableComposerAboveTheDetail() {
        composeRule.runOnIdle { viewModel.openPostDetail("123") }
        waitForDetail("123")
        replyAndType("123")
        composeRule.onNodeWithTag("close-composer").performClick()
        assertEquals("123", viewModel.state.value.postDetail.postId)
        composeRule.onNodeWithTag("post-detail-replies").performScrollToKey("124")
        composeRule.onNodeWithTag("post-body-124", useUnmergedTree = true).performTouchInput { click() }
        waitForDetail("124")
        replyAndType("124")
        composeRule.onNodeWithTag("close-composer").performClick()
        assertEquals("124", viewModel.state.value.postDetail.postId)
    }

    @Test
    fun profileReplyKeepsTheProfileAndOpensAnEditableComposer() {
        composeRule.runOnIdle { viewModel.userProfileController!!.open("42") }
        awaitState("profile ready") { viewModel.state.value.userProfile.status == UserProfileStatus.READY }
        composeRule.onNodeWithTag("user-profile-posts")
            .performScrollToNode(hasTestTag("post-action-reply-123"))
        replyAndType("123")
        assertEquals("42", viewModel.state.value.userProfile.userId)
        composeRule.onNodeWithTag("close-composer").performClick()
        composeRule.onNodeWithTag("user-profile-route").assertIsDisplayed()
    }

    private fun replyAndType(id: String) {
        composeRule.onNodeWithTag("post-action-reply-$id").performClick()
        composeRule.onNodeWithTag("composer-text").assertIsDisplayed().performTextInput("reply fixture")
        composeRule.onNodeWithTag("composer-text").assertTextContains("reply fixture")
        assertEquals(ComposerMode.REPLY, viewModel.state.value.composer.mode)
        assertEquals(id, viewModel.state.value.composer.targetPostId)
        assertTrue(viewModel.state.value.postDetail.postId != null || viewModel.state.value.userProfile.userId != null)
    }

    private fun waitForDetail(id: String) {
        awaitState("detail $id ready") { viewModel.state.value.postDetail.postId == id &&
            viewModel.state.value.postDetail.status == PostDetailStatus.READY &&
            !viewModel.state.value.postDetail.isLoadingMore }
        composeRule.onNodeWithTag("post-detail-replies").performScrollToKey("detail-post")
    }

    private fun tweet(id: Int): String {
        val parent = if (id == 123) "null" else "\"${id - 1}\""
        return """{"rest_id":"$id","legacy":{"full_text":"reply fixture $id","lang":"en","in_reply_to_status_id_str":$parent,"created_at":"Sat Aug 29 00:00:00 +0000 2026"},"core":{"user_results":{"result":{"rest_id":"7","legacy":{"screen_name":"user","name":"User"}}}}}"""
    }

    private fun awaitState(label: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(10_000) {
                condition() || viewModel.state.value.postDetail.status == PostDetailStatus.FAILED ||
                    viewModel.state.value.userProfile.status == UserProfileStatus.FAILED
            }
        } catch (failure: Exception) {
            throw AssertionError("$label did not complete: ${diagnostics()}", failure)
        }
        assertTrue("$label failed: ${diagnostics()}", condition())
    }

    /** Deliberately excludes authentication fields, session values, and account files. */
    private fun diagnostics(): String {
        val state = viewModel.state.value
        return "initializing=${state.isInitializing}, hasAccount=${state.selectedAccountId != null}, " +
            "schema=${DeckSettingsStore.CURRENT_SCHEMA_VERSION}, detail=${state.postDetail.status}, " +
            "detailLoading=${state.postDetail.isLoadingMore}, detailFailed=${state.postDetail.loadMoreFailed}, " +
            "profile=${state.userProfile.status}, profilePosts=${state.userProfile.posts.size}, " +
            "purposes=$requests, fixtureErrors=$fixtureFailures"
    }
}
