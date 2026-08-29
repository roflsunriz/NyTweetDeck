package dev.nytweetdeck.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveReadOnlyParitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun detailTranslationMediaAndLiveStatusWorkWithTheSavedXSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.noBackupFilesDir, "accounts/accounts.json").isFile)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("timeline-posts").fetchSemanticsNodes().isNotEmpty()
        }

        val firstPost = composeRule.onAllNodes(postCardMatcher())[0]
        firstPost.performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("post-detail-loading").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("post-detail").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("post-detail-retry").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("close-post-detail").performClick()

        val timeline = composeRule.onAllNodesWithTag("timeline-posts").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex]
        }
        var translationVerified = false
        var mediaVerified = false
        val maximum = minOf(postCount(), 30)
        for (index in 0 until maximum) {
            timeline.performScrollToIndex(index)
            composeRule.waitForIdle()
            if (!translationVerified) {
                translationVerified =
                    composeRule.onAllNodesWithTag("translation-toggle").fetchSemanticsNodes().isNotEmpty()
            }
            if (!translationVerified &&
                composeRule.onAllNodesWithTag("translation-loading").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.waitUntil(30_000) {
                    composeRule.onAllNodesWithTag("translation-toggle").fetchSemanticsNodes().isNotEmpty() ||
                        composeRule.onAllNodesWithTag("translation-retry").fetchSemanticsNodes().isNotEmpty()
                }
                translationVerified =
                    composeRule.onAllNodesWithTag("translation-toggle").fetchSemanticsNodes().isNotEmpty()
                if (translationVerified) {
                    composeRule.onAllNodesWithTag("translation-toggle")[0].performClick()
                }
            }
            val media = composeRule.onAllNodes(mediaMatcher())
            if (!mediaVerified && media.fetchSemanticsNodes().isNotEmpty()) {
                media[0].performClick()
                composeRule.onNodeWithTag("media-viewer").assertIsDisplayed()
                composeRule.onNodeWithTag("media-close").performClick()
                mediaVerified = true
            }
            if (translationVerified && mediaVerified) break
        }
        assertTrue("取得した30件以内にX翻訳対象がありませんでした。", translationVerified)
        assertTrue("取得した30件以内にメディア付きポストがありませんでした。", mediaVerified)

        composeRule.onNodeWithTag("settings").performClick()
        composeRule.onNodeWithTag("live-pipeline-status").performScrollTo()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.live_pipeline_connected),
        ).assertIsDisplayed()
    }

    private fun postCount(): Int {
        val matcher = SemanticsMatcher("timeline post count") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("timeline-post-count-")
        }
        val node = composeRule.onAllNodes(matcher).fetchSemanticsNodes().lastOrNull() ?: return 0
        return node.config[SemanticsProperties.TestTag]
            .removePrefix("timeline-post-count-")
            .toIntOrNull() ?: 0
    }

    private fun postCardMatcher() = SemanticsMatcher("post card") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].matches(Regex("post-[0-9]{1,30}"))
    }

    private fun mediaMatcher() = SemanticsMatcher("post media") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].startsWith("post-media-")
    }
}
