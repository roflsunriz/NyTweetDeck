package dev.nytweetdeck.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.model.ColumnKind
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CachedTimelineSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cachedHomeIsVisibleImmediatelyAfterColdStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.noBackupFilesDir, "accounts/accounts.json").isFile)
        assumeTrue(context.cacheDir.resolve("timelines").listFiles()?.isNotEmpty() == true)

        val homeColumnId = DeckSettingsStore(
            context.filesDir.toPath().resolve("layout/settings.json"),
        ).load().columns.last { it.kind == ColumnKind.HOME_FOR_YOU }.id
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("deck-columns").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("deck-columns").performScrollToKey(homeColumnId)
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodes(
                hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
