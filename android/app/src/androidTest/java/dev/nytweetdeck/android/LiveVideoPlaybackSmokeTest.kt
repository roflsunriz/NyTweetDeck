package dev.nytweetdeck.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.model.ColumnKind
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveVideoPlaybackSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun realVideoPlaysAndReusesTheSharedDiskCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.noBackupFilesDir, "accounts/accounts.json").isFile)
        val columns = DeckSettingsStore(
            context.filesDir.toPath().resolve("layout/settings.json"),
        ).load().columns
        val homeIndex = columns.indexOfLast { it.kind == ColumnKind.HOME_FOR_YOU }
        assumeTrue("おすすめカラムがない環境では実動画テストを省略します。", homeIndex >= 0)
        val homeColumnId = columns[homeIndex].id
        val homeAncestor = hasAnyAncestor(hasTestTag("column-$homeColumnId"))

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("deck-columns").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("deck-columns").performScrollToIndex(homeIndex)
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodes(hasTestTag("timeline-posts") and homeAncestor)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val timeline = composeRule.onNode(hasTestTag("timeline-posts") and homeAncestor)
        val inspected = hashSetOf<String>()
        var videoTag: String? = null
        var videoAttempts = 0
        val maximum = minOf(postCount(homeColumnId), 60)

        search@ for (index in 0 until maximum) {
            timeline.performScrollToIndex(index)
            composeRule.waitForIdle()
            val mediaNodes = composeRule.onAllNodes(mediaMatcher() and homeAncestor)
                .fetchSemanticsNodes()
            for (node in mediaNodes) {
                val tag = node.config[SemanticsProperties.TestTag]
                if (!inspected.add(tag)) continue
                composeRule.onNode(hasTestTag(tag) and homeAncestor)
                    .performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodesWithTag("media-viewer").fetchSemanticsNodes().isNotEmpty()
                }
                if (composeRule.onAllNodesWithTag("media-video").fetchSemanticsNodes().isNotEmpty()) {
                    videoAttempts++
                    if (ensureVideoPlaying(20_000)) {
                        videoTag = tag
                        break@search
                    }
                    composeRule.onNodeWithTag("media-close").performClick()
                    if (videoAttempts >= 5) break@search
                    continue
                }
                composeRule.onNodeWithTag("media-close").performClick()
            }
        }

        assertTrue("取得した60件以内に実動画がありませんでした。", videoTag != null)
        composeRule.waitUntil(60_000) { videoCacheBytes(context.cacheDir) > 0L }
        val firstCacheBytes = videoCacheBytes(context.cacheDir)
        composeRule.onNodeWithTag("media-close").performClick()

        composeRule.onNode(hasTestTag(requireNotNull(videoTag)) and homeAncestor)
            .performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("media-video").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("キャッシュ済み動画を再生できませんでした。", ensureVideoPlaying(60_000))
        assertTrue(
            "同じ動画を開き直した際に共有キャッシュが失われました。",
            videoCacheBytes(context.cacheDir) >= firstCacheBytes,
        )
        composeRule.onNodeWithTag("media-close").performClick()
    }

    private fun ensureVideoPlaying(timeoutMillis: Long): Boolean {
        if (composeRule.onAllNodesWithTag("media-video-playing", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("media-play").performClick()
        }
        return runCatching {
            composeRule.waitUntil(timeoutMillis) {
                composeRule.onAllNodesWithTag("media-video-playing", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
    }

    private fun postCount(columnId: String): Int {
        val matcher = SemanticsMatcher("timeline post count") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("timeline-post-count-")
        }
        val node = composeRule.onAllNodes(
            matcher and hasAnyAncestor(hasTestTag("column-$columnId")),
        ).fetchSemanticsNodes().singleOrNull() ?: return 0
        return node.config[SemanticsProperties.TestTag]
            .removePrefix("timeline-post-count-")
            .toIntOrNull() ?: 0
    }

    private fun mediaMatcher() = SemanticsMatcher("post media") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].startsWith("post-media-")
    }

    private fun videoCacheBytes(cacheDir: File): Long =
        cacheDir.resolve("video-playback")
            .walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
}
