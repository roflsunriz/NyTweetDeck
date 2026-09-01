package dev.nytweetdeck.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.model.ColumnKind
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveUserProfileRepliesSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authorProfileRepliesLoadAndBackRestoresTheDeck() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.noBackupFilesDir, "accounts/accounts.json").isFile)
        val homeColumnId = DeckSettingsStore(
            context.filesDir.toPath().resolve("layout/settings.json"),
        ).load().columns.last { it.kind == ColumnKind.HOME_FOR_YOU }.id
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("deck-columns").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("deck-columns").performScrollToKey(homeColumnId)
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodes(
                authorMatcher() and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(
            authorMatcher() and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
        )[0].performClick()
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithTag("user-profile-header").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("profile-tab-replies").performClick()
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithTag("user-profile-loading").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("user-profile-posts").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("user-profile-retry").fetchSemanticsNodes().isEmpty())

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("user-profile-route").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("deck-columns").fetchSemanticsNodes().isNotEmpty())
    }

    private fun authorMatcher() = SemanticsMatcher("post author") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].matches(Regex("post-author-[0-9]{1,24}"))
    }
}
