package dev.nytweetdeck.android

import android.app.DownloadManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.model.ColumnKind
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
        val homeColumnId = DeckSettingsStore(
            context.filesDir.toPath().resolve("layout/settings.json"),
        ).load().columns.last { it.kind == ColumnKind.HOME_FOR_YOU }.id
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("deck-columns").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("deck-columns").performScrollToKey(homeColumnId)
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodes(
                hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        val timeline = composeRule.onNode(
            hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
        )
        timeline.performScrollToIndex(0)
        val initialGeneration = refreshGeneration(homeColumnId)
        timeline.performTouchInput { swipeDown(durationMillis = 650) }
        composeRule.waitUntil(60_000) {
            refreshGeneration(homeColumnId) > initialGeneration
        }

        val firstPost = composeRule.onAllNodes(
            postCardMatcher() and hasAnyAncestor(hasTestTag("column-$homeColumnId")),
        )[0]
        firstPost.performClick()
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodesWithTag("post-detail-loading").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("post-detail").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("post-detail-retry").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("close-post-detail").performClick()

        var translationVerified = false
        var mediaVerified = false
        var mediaPostId: String? = null
        val downloadManager = context.getSystemService(DownloadManager::class.java)
        val downloadsBefore = downloadIds(downloadManager)
        val homeAncestor = hasAnyAncestor(hasTestTag("column-$homeColumnId"))
        val maximum = minOf(postCount(homeColumnId), 30)
        for (index in 0 until maximum) {
            timeline.performScrollToIndex(index)
            composeRule.waitForIdle()
            if (!translationVerified) {
                translationVerified =
                    composeRule.onAllNodes(
                        hasTestTag("translation-toggle") and homeAncestor,
                    ).fetchSemanticsNodes().isNotEmpty()
            }
            if (!translationVerified &&
                composeRule.onAllNodes(
                    hasTestTag("translation-loading") and homeAncestor,
                ).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.waitUntil(90_000) {
                    composeRule.onAllNodes(
                        hasTestTag("translation-toggle") and homeAncestor,
                    ).fetchSemanticsNodes().isNotEmpty() ||
                        composeRule.onAllNodes(
                            hasTestTag("translation-retry") and homeAncestor,
                        ).fetchSemanticsNodes().isNotEmpty()
                }
                translationVerified =
                    composeRule.onAllNodes(
                        hasTestTag("translation-toggle") and homeAncestor,
                    ).fetchSemanticsNodes().isNotEmpty()
                if (translationVerified) {
                    composeRule.onAllNodes(
                        hasTestTag("translation-toggle") and homeAncestor,
                    )[0].performClick()
                }
            }
            val media = composeRule.onAllNodes(mediaMatcher() and homeAncestor)
            val mediaNodes = media.fetchSemanticsNodes()
            val mediaIndex = mediaNodes.indexOfFirst { node ->
                val candidatePostId = node.config[SemanticsProperties.TestTag]
                    .removePrefix("post-media-")
                    .substringBefore('-')
                composeRule.onAllNodes(
                    hasTestTag("post-action-download-$candidatePostId") and homeAncestor,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            if (!mediaVerified && mediaIndex >= 0) {
                mediaPostId = mediaNodes[mediaIndex].config[SemanticsProperties.TestTag]
                    .removePrefix("post-media-")
                    .substringBefore('-')
                composeRule.onNode(
                    hasTestTag("post-action-download-" + requireNotNull(mediaPostId)) and homeAncestor,
                ).performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
                media[mediaIndex].performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodesWithTag("media-viewer").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("media-viewer").assertIsDisplayed()
                composeRule.onNodeWithTag("media-close").performClick()
                mediaVerified = true
            }
            if (translationVerified && mediaVerified) break
        }
        assertTrue("取得した30件以内にX翻訳対象がありませんでした。", translationVerified)
        assertTrue("取得した30件以内にメディア付きポストがありませんでした。", mediaVerified)
        verifyMediaDownload(downloadManager, downloadsBefore)

        composeRule.onNodeWithTag("settings").performClick()
        composeRule.onNodeWithTag("live-pipeline-status").performScrollTo()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.live_pipeline_connected),
        ).assertIsDisplayed()
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

    private fun postCardMatcher() = SemanticsMatcher("post card") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].matches(Regex("post-[0-9]{1,30}"))
    }

    private fun mediaMatcher() = SemanticsMatcher("post media") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].startsWith("post-media-")
    }

    private fun verifyMediaDownload(manager: DownloadManager, before: Set<Long>) {
        var created = emptySet<Long>()
        try {
            composeRule.waitUntil(120_000) {
                created = downloadIds(manager) - before
                created.isNotEmpty() && downloadStatuses(manager, created).all {
                    it == DownloadManager.STATUS_SUCCESSFUL || it == DownloadManager.STATUS_FAILED
                }
            }
            assertTrue(
                "実メディアのダウンロードが成功しませんでした。",
                downloadStatuses(manager, created).all { it == DownloadManager.STATUS_SUCCESSFUL },
            )
        } finally {
            if (created.isNotEmpty()) manager.remove(*created.toLongArray())
        }
    }

    private fun downloadIds(manager: DownloadManager): Set<Long> =
        manager.query(DownloadManager.Query()).use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            buildSet {
                while (cursor.moveToNext()) add(cursor.getLong(idColumn))
            }
        }

    private fun downloadStatuses(manager: DownloadManager, ids: Set<Long>): List<Int> =
        manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { cursor ->
            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            buildList {
                while (cursor.moveToNext()) add(cursor.getInt(statusColumn))
            }
        }
}
