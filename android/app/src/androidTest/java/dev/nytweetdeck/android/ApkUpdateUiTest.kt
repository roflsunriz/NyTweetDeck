package dev.nytweetdeck.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.ui.SettingsDialog
import dev.nytweetdeck.android.ui.TransferStatus
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.update.ApkUpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ApkUpdateUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun updateButtonFollowsEligibilityAndAllowsRetry() {
        val status = mutableStateOf(ApkUpdateStatus.NONE)
        var clicks = 0
        composeRule.setContent {
            NyTweetDeckTheme {
                SettingsDialog(
                    state = DeckUiState(),
                    onDisplaySettingsChange = {},
                    onExport = {}, onImport = {}, onDismiss = {},
                    transferStatus = TransferStatus.NONE,
                    apkUpdateStatus = status.value,
                    onDownloadLatestApk = { clicks++; status.value = ApkUpdateStatus.DOWNLOAD_STARTED },
                )
            }
        }
        val button = composeRule.onNodeWithTag("download-latest-apk")
        for (disabled in listOf(ApkUpdateStatus.NONE, ApkUpdateStatus.CHECKING, ApkUpdateStatus.UP_TO_DATE, ApkUpdateStatus.DOWNLOAD_STARTED)) {
            composeRule.runOnIdle { status.value = disabled }
            button.performScrollTo().assertIsNotEnabled().performClick()
        }
        composeRule.runOnIdle { assertEquals(0, clicks); status.value = ApkUpdateStatus.AVAILABLE }
        button.assertIsEnabled().performClick()
        button.assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks); status.value = ApkUpdateStatus.FAILED }
        button.assertIsEnabled().performClick()
        button.assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(2, clicks) }
    }
}
