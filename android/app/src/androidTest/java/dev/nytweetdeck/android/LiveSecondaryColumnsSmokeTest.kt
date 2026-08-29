package dev.nytweetdeck.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveSecondaryColumnsSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authenticatedNotificationsTrendsAndMessagesRender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.noBackupFilesDir, "accounts/accounts.json").isFile)

        composeRule.onNodeWithTag("menu-home").performClick()
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty()
        }
        val timelines = composeRule.onAllNodesWithTag("timeline-posts")
        val timeline = timelines[timelines.fetchSemanticsNodes().lastIndex]
        timeline.performScrollToIndex(0)
        val initialGeneration = refreshGeneration()
        timeline.performTouchInput { swipeDown(durationMillis = 650) }
        composeRule.waitUntil(120_000) { refreshGeneration() > initialGeneration }

        activateAndAwait("menu-notifications", setOf("notification-list", "notification-empty"), "notification-load-failed")
        activateAndAwait("menu-trends", setOf("trend-list"), "trend-load-failed")
        activateAndAwait("menu-messages", setOf("message-list", "message-empty"), "message-load-failed")
    }

    private fun activateAndAwait(menuTag: String, resultTags: Set<String>, failureTag: String) {
        composeRule.onNodeWithTag(menuTag).performClick()
        composeRule.waitUntil(timeoutMillis = 120_000) {
            resultTags.any { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() } ||
                composeRule.onAllNodesWithTag(failureTag).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(resultTags.any { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() })
    }

    private fun refreshGeneration(): Long {
        val matcher = SemanticsMatcher("refresh generation") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("timeline-refresh-generation-")
        }
        val node = composeRule.onAllNodes(matcher).fetchSemanticsNodes().lastOrNull() ?: return -1
        return node.config[SemanticsProperties.TestTag]
            .removePrefix("timeline-refresh-generation-")
            .toLongOrNull() ?: -1
    }
}
