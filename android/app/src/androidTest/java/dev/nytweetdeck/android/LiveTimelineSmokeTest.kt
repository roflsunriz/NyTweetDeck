package dev.nytweetdeck.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.model.ColumnKind
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

        val hasHomeForYou = DeckSettingsStore(
            context.filesDir.toPath().resolve("layout/settings.json"),
        ).load().columns.any { it.kind == ColumnKind.HOME_FOR_YOU }
        if (!hasHomeForYou) {
            if (composeRule.onAllNodesWithTag("empty-deck").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("empty-deck").performClick()
            } else {
                composeRule.onNodeWithTag("inline-add-column").performScrollTo().performClick()
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("add-home_for_you").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("add-home_for_you").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("timeline-load-failed").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(composeRule.onAllNodes(hasTestTag("timeline-posts")).fetchSemanticsNodes().isNotEmpty())

        val homeColumnId = DeckSettingsStore(
            context.filesDir.toPath().resolve("layout/settings.json"),
        ).load().columns.last { it.kind == ColumnKind.HOME_FOR_YOU }.id
        val initialCount = postCount(homeColumnId)
        assertTrue(initialCount > 0)
        composeRule.onNode(
            hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
        ).performScrollToIndex(initialCount - 1)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            postCount(homeColumnId) > initialCount ||
                composeRule.onAllNodesWithTag("load-more-failed").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("cursor追加後に投稿件数が増えませんでした。", postCount(homeColumnId) > initialCount)
    }

    private fun postCount(columnId: String): Int {
        val matcher = SemanticsMatcher("timeline post count") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("timeline-post-count-")
        }
        val node = composeRule.onAllNodes(
            matcher and hasAnyAncestor(hasTestTag("column-$columnId")),
        ).fetchSemanticsNodes().singleOrNull() ?: return 0
        val tag = node.config[SemanticsProperties.TestTag]
        return tag.removePrefix("timeline-post-count-").toIntOrNull() ?: 0
    }
}
