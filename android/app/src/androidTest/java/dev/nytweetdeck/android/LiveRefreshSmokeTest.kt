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

class LiveRefreshSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pullAtTopRefreshesAndKeepsTimelineUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.noBackupFilesDir, "accounts/accounts.json").isFile)

        composeRule.onNodeWithTag("menu-home").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty()
        }
        val timelineNodes = composeRule.onAllNodesWithTag("timeline-posts")
        val selectedTimeline = timelineNodes[timelineNodes.fetchSemanticsNodes().lastIndex]
        selectedTimeline.performScrollToIndex(0)
        val initialGeneration = refreshGeneration()
        selectedTimeline.performTouchInput { swipeDown(durationMillis = 650) }
        composeRule.waitUntil(30_000) {
            refreshGeneration() > initialGeneration
        }
        assertTrue(composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty())
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
