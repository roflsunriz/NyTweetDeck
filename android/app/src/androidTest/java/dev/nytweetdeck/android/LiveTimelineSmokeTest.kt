package dev.nytweetdeck.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveTimelineSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authenticatedSavedHomeColumnRendersRealPosts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountFile = File(context.noBackupFilesDir, "accounts/accounts.json")
        assumeTrue("保存済み検証アカウントがない環境では実Xテストを省略します。", accountFile.isFile)

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("timeline-load-failed").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(composeRule.onAllNodes(hasTestTag("timeline-posts")).fetchSemanticsNodes().isNotEmpty())

        val initialCount = postCount()
        assertTrue(initialCount > 0)
        composeRule.onNodeWithTag("timeline-posts").performScrollToIndex(initialCount - 1)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            postCount() > initialCount ||
                composeRule.onAllNodesWithTag("load-more-failed").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("cursor追加後に投稿件数が増えませんでした。", postCount() > initialCount)
    }

    private fun postCount(): Int {
        val matcher = SemanticsMatcher("timeline post count") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("timeline-post-count-")
        }
        val node = composeRule.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull() ?: return 0
        val tag = node.config[SemanticsProperties.TestTag]
        return tag.removePrefix("timeline-post-count-").toIntOrNull() ?: 0
    }
}
