package dev.nytweetdeck.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveSecondaryColumnsSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authenticatedNotificationsTrendsAndMessagesRender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.noBackupFilesDir, "accounts/accounts.json").isFile)

        activateAndAwait("menu-notifications", setOf("notification-list", "notification-empty"), "notification-load-failed")
        activateAndAwait("menu-trends", setOf("trend-list"), "trend-load-failed")
        activateAndAwait("menu-messages", setOf("message-list", "message-empty"), "message-load-failed")
    }

    private fun activateAndAwait(menuTag: String, resultTags: Set<String>, failureTag: String) {
        composeRule.onNodeWithTag(menuTag).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            resultTags.any { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() } ||
                composeRule.onAllNodesWithTag(failureTag).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(resultTags.any { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() })
    }
}
