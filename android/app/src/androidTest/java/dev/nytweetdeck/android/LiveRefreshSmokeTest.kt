package dev.nytweetdeck.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.model.ColumnKind
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

        val homeColumnId = DeckSettingsStore(
            context.filesDir.toPath().resolve("layout/settings.json"),
        ).load().columns.last { it.kind == ColumnKind.HOME_FOR_YOU }.id
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("deck-columns").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("deck-columns").performScrollToKey(homeColumnId)
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodes(
                hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        val selectedTimeline = composeRule.onNode(
            hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
        )
        selectedTimeline.performScrollToIndex(0)
        val initialGeneration = refreshGeneration(homeColumnId)
        selectedTimeline.performTouchInput { swipeDown(durationMillis = 650) }
        composeRule.waitUntil(120_000) {
            refreshGeneration(homeColumnId) > initialGeneration
        }
        assertTrue(composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty())
    }

    private fun refreshGeneration(columnId: String): Long {
        val matcher = SemanticsMatcher("refresh generation") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("timeline-refresh-generation-")
        }
        val node = composeRule.onAllNodes(
            matcher and hasAnyAncestor(hasTestTag("column-$columnId")),
        ).fetchSemanticsNodes().singleOrNull() ?: return -1
        return node.config[SemanticsProperties.TestTag]
            .removePrefix("timeline-refresh-generation-")
            .toLongOrNull() ?: -1
    }
}
