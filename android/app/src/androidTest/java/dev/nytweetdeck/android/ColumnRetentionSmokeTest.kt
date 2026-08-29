package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.ui.DeckViewModel
import dev.nytweetdeck.android.ui.NyTweetDeckApp
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.XSessionVerifier
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ColumnRetentionSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun horizontalSwitchShowsRetainedTimelineImmediatelyAndRestoresExactAnchor() {
        val testRoot = composeRule.activity.cacheDir.resolve("retention-${UUID.randomUUID()}")
        val settingsStore = DeckSettingsStore(testRoot.resolve("settings.json").toPath())
        val source = DeckColumn("retained-home", ColumnKind.HOME_FOR_YOU, "Home")
        val destination = DeckColumn("retained-following", ColumnKind.HOME_FOLLOWING, "Following")
        settingsStore.save(DeckUiState(columns = listOf(source, destination)))
        val accountFile = testRoot.resolve("accounts.json")
        AccountStore(accountFile).addOrReplace(
            AccountSecrets(
                "fixture", "fixture", "fixture", "Fixture", "bearer", "auth", "csrf", "profile",
            ),
            select = true,
        )
        val repository = TimelineRepository(GraphQlExecutor { _, _, _, _ -> fixtureTimeline() })
        val viewModel = DeckViewModel(
            settingsStore = settingsStore,
            accountStoreFile = accountFile,
            sessionVerifier = XSessionVerifier { error("not used") },
            timelineRepository = repository,
        )
        composeRule.activity.setContent { NyTweetDeckApp(providedViewModel = viewModel) }
        val sourceIndex = 0
        val destinationIndex = 1

        composeRule.onNodeWithTag("deck-columns").performScrollToIndex(sourceIndex)
        composeRule.waitUntil(30_000) {
            timelineNodes(source.id).isNotEmpty() ||
                composeRule.onAllNodesWithTag("timeline-load-failed").fetchSemanticsNodes().isNotEmpty()
        }
        val count = postCount(source.id)
        assertTrue(count >= 20)
        timelineNode(source.id).performScrollToIndex(minOf(5, count - 1))
        composeRule.waitForIdle()
        val before = firstVisiblePost(source.id)

        composeRule.onNodeWithTag("deck-columns").performScrollToIndex(destinationIndex)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("deck-columns").performScrollToIndex(sourceIndex)
        composeRule.waitUntil(1_000) { timelineNodes(source.id).isNotEmpty() }

        val sourceColumn = hasTestTag("column-${source.id}")
        assertTrue(
            "カラム復帰時に保持データではなく読み込み表示へ戻りました。",
            composeRule.onAllNodes(
                hasTestTag("timeline-loading") and hasAnyAncestor(sourceColumn),
            ).fetchSemanticsNodes().isEmpty(),
        )
        val after = firstVisiblePost(source.id)
        assertEquals("復帰後の先頭投稿が変わりました。", before.tag, after.tag)
        assertTrue(
            "復帰後のpixel offsetが変わりました: before=${before.top}, after=${after.top}",
            abs(before.top - after.top) <= 1.0f,
        )
    }

    private fun timelineNode(columnId: String) = composeRule.onNode(
        hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$columnId")),
    )

    private fun timelineNodes(columnId: String) = composeRule.onAllNodes(
        hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$columnId")),
    ).fetchSemanticsNodes()

    private fun postCount(columnId: String): Int {
        val matcher = SemanticsMatcher("timeline post count in selected column") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("timeline-post-count-")
        } and hasAnyAncestor(hasTestTag("column-$columnId"))
        val node = composeRule.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull() ?: return 0
        return node.config[SemanticsProperties.TestTag]
            .removePrefix("timeline-post-count-")
            .toIntOrNull() ?: 0
    }

    private fun firstVisiblePost(columnId: String): VisiblePost {
        val columnBounds = composeRule.onNodeWithTag("column-$columnId")
            .fetchSemanticsNode().boundsInRoot
        val postMatcher = SemanticsMatcher("post card") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("post-")
        } and hasAnyAncestor(hasTestTag("column-$columnId"))
        val visible = composeRule.onAllNodes(postMatcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { node ->
                node.boundsInRoot.bottom > columnBounds.top &&
                    node.boundsInRoot.top < columnBounds.bottom
            }
            .minBy { it.boundsInRoot.top }
        return VisiblePost(
            tag = visible.config[SemanticsProperties.TestTag],
            top = visible.boundsInRoot.top,
        )
    }

    private data class VisiblePost(val tag: String, val top: Float)

    private fun fixtureTimeline(): String = buildString {
        append("{\"data\":{\"entries\":[")
        repeat(40) { index ->
            if (index > 0) append(',')
            append("""{"content":{"itemContent":{"tweet_results":{"result":{
                "__typename":"Tweet","rest_id":"${1000 - index}",
                "legacy":{"full_text":"Fixture post $index",
                "created_at":"Sat Aug 29 00:00:00 +0000 2026"},
                "core":{"user_results":{"result":{"__typename":"User","rest_id":"fixture",
                "core":{"screen_name":"fixture","name":"Fixture"}}}}
            }}}}}""")
        }
        append("]}}")
    }
}
