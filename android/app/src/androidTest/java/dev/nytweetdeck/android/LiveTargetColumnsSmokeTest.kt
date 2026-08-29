package dev.nytweetdeck.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountStore
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveTargetColumnsSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun userListAndSearchTargetsResolveAndRender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountFile = File(context.noBackupFilesDir, "accounts/accounts.json")
        assumeTrue(accountFile.isFile)
        val username = requireNotNull(AccountStore(accountFile).selectedAccount()).username

        openTarget("add-user", username)
        awaitTimelineResult()

        composeRule.onNodeWithTag("menu-search").performClick()
        composeRule.onNodeWithTag("add-list").performScrollTo().performClick()
        val optionMatcher = SemanticsMatcher("list option") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("list-option-")
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(optionMatcher).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("target-picker-failed").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodes(optionMatcher).fetchSemanticsNodes().isNotEmpty())
        composeRule.onAllNodes(optionMatcher)[0].performClick()
        awaitTimelineResult()

        openTarget("add-search", "NyTweetDeck")
        awaitTimelineResult()

        composeRule.onNodeWithTag("menu-search").performClick()
        composeRule.onNodeWithTag("add-home_following").performScrollTo().performClick()
        awaitTimelineResult()

        composeRule.onNodeWithTag("menu-search").performClick()
        composeRule.onNodeWithTag("add-history").performScrollTo().performClick()
        awaitTimelineResult()
    }

    private fun openTarget(addTag: String, value: String) {
        composeRule.onNodeWithTag("menu-search").performClick()
        composeRule.onNodeWithTag(addTag).performScrollTo().performClick()
        composeRule.onNodeWithTag("column-target").performTextInput(value)
        composeRule.onNodeWithTag("confirm-target-column").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("column-target").fetchSemanticsNodes().isEmpty() ||
                composeRule.onAllNodesWithTag("target-picker-failed").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("target-picker-failed").fetchSemanticsNodes().isEmpty())
    }

    private fun awaitTimelineResult() {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("timeline-empty").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("timeline-load-failed").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("timeline-load-failed").fetchSemanticsNodes().isEmpty())
    }
}
