package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.data.TrendRepository
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
        val trends = DeckColumn("retained-trends", ColumnKind.TRENDS, "Trends")
        settingsStore.save(DeckUiState(columns = listOf(source, destination, trends)))
        val accountFile = testRoot.resolve("accounts.json")
        AccountStore(accountFile).addOrReplace(
            AccountSecrets(
                "fixture", "fixture", "fixture", "Fixture", "bearer", "auth", "csrf", "profile",
            ),
            select = true,
        )
        val executor = GraphQlExecutor { _, purpose, variables, _ ->
            when (purpose) {
                "postDetail" -> detailFixture(requireNotNull(variables["tweetId"] as? String))
                "conversation" -> conversationFixture(
                    requireNotNull(variables["focalTweetId"] as? String),
                )
                "trends" -> if (variables["cursor"] == null) {
                    trendFixture("#NyTweetDeck", "trend-next")
                } else {
                    trendFixture("#Android", null)
                }
                else -> fixtureTimeline()
            }
        }
        val repository = TimelineRepository(executor)
        val viewModel = DeckViewModel(
            settingsStore = settingsStore,
            accountStoreFile = accountFile,
            sessionVerifier = XSessionVerifier { error("not used") },
            timelineRepository = repository,
            postDetailRepository = PostDetailRepository(executor),
            trendRepository = TrendRepository(executor),
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
        listOf(
            "post-action-reply-",
            "post-action-repost-",
            "post-action-like-",
            "post-action-impressions-",
            "post-action-bookmark-",
            "post-action-share-",
            "post-action-download-",
        ).forEach { prefix ->
            val matcher = SemanticsMatcher("post action $prefix") { node ->
                node.config.contains(SemanticsProperties.TestTag) &&
                    node.config[SemanticsProperties.TestTag].startsWith(prefix)
            }
            assertTrue(composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty())
        }
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

        composeRule.onNodeWithTag(after.tag).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("reply-sort-relevance").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("reply-sort-recency").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("post-detail-loading").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("deemphasized-replies").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("post-701").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("close-post-detail").performClick()
        composeRule.onNodeWithTag("deck-columns").performScrollToIndex(2)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("trend-list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            viewModel.state.value.trends[trends.id]?.page?.trends?.size == 2
        }
        composeRule.onNodeWithTag("trend-filter").performTextInput("Ny")
        composeRule.onNodeWithTag("trend-filter").performImeAction()
        assertEquals("Ny", viewModel.state.value.trendSearchHistory.first())
        composeRule.onNodeWithTag("trend-item-${"#NyTweetDeck".hashCode()}").performClick()
        assertTrue(viewModel.state.value.columns.any {
            it.kind == ColumnKind.SEARCH && it.target == "#NyTweetDeck"
        })
        if (InstrumentationRegistry.getArguments().getString("capture") == "true") {
            Thread.sleep(5_000)
        }
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

    private fun detailFixture(postId: String): String =
        """{"data":{"tweet":{"result":${tweetFixture(postId, "focal")}}}}"""

    private fun conversationFixture(postId: String): String = """
        {"data":{"threaded_conversation_with_injections_v2":{"instructions":[{"entries":[
          ${conversationEntry(postId, "focal", "HighQuality", null)},
          ${conversationEntry("701", "low", "LowQuality", postId)},
          ${conversationEntry("702", "abusive", "AbusiveQuality", postId)},
          ${conversationEntry("703", "normal", "HighQuality", postId)}
        ]}]}}}
    """.trimIndent()

    private fun conversationEntry(id: String, text: String, section: String, parent: String?): String {
        val reply = parent?.let { ",\"in_reply_to_status_id_str\":\"$it\"" }.orEmpty()
        return """{"content":{"clientEventInfo":{"details":{"conversationDetails":
          {"conversationSection":"$section"}}},"itemContent":{"tweet_results":{"result":
          ${tweetFixture(id, text, reply)}}}}}"""
    }

    private fun tweetFixture(id: String, text: String, legacySuffix: String = ""): String =
        """{"__typename":"Tweet","rest_id":"$id","legacy":{"full_text":"$text"$legacySuffix}}"""

    private fun trendFixture(name: String, cursor: String?): String {
        val cursorEntry = cursor?.let {
            """,{"content":{"entryType":"TimelineTimelineCursor","cursorType":"Bottom",
            "value":"$it"}}"""
        }.orEmpty()
        return """
        {"data":{"explore_page":{"body":{"initialTimeline":{"timeline":{"timeline":
        {"instructions":[{"entries":[{"content":{"entryType":"TimelineTimelineItem",
        "itemContent":{"__typename":"TimelineTrend","itemType":"TimelineTrend",
        "name":"$name","description":"1,234 posts","rank":"1",
        "trend_metadata":{"domain_context":"Technology"},
        "trend_url":{"url":"twitter://search?query=$name"}}}}$cursorEntry]}]}}}}}}}
    """.trimIndent()
    }
}
