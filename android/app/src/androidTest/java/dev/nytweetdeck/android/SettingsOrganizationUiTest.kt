package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.ui.SettingsDialog
import dev.nytweetdeck.android.ui.AppLocaleController
import dev.nytweetdeck.android.ui.TransferStatus
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

class SettingsOrganizationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun helpAndDetailsCanBeOpenedAndClosedAndErrorsAreRevealed() {
        val state = mutableStateOf(DeckUiState())
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                SettingsDialog(
                    state = state.value,
                    onDisplaySettingsChange = {},
                    onExport = {}, onImport = {}, onDismiss = {},
                    transferStatus = TransferStatus.NONE,
                )
            }
        }
        composeRule.onNodeWithTag("translation-health").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-help-navigation").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-help").performScrollTo().performClick()
        for (tag in listOf("refresh", "navigation", "update")) {
            composeRule.onNodeWithTag("settings-help-$tag").performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithTag("settings-help").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings-help-navigation").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-details").performScrollTo().performClick()
        composeRule.onNodeWithTag("translation-health").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("refresh-x-api-metadata").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("settings-details").performScrollTo().performClick()
        composeRule.onNodeWithTag("translation-health").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(xApiMetadataError = true) }
        composeRule.onNodeWithTag("x-api-metadata-status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings-help-navigation").assertDoesNotExist()
    }

    @Test
    fun languageMenusKeepAllChoicesAndDismissWithoutChangingSelection() {
        val language = mutableStateOf("ja")
        val translationLanguage = mutableStateOf("ja")
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                SettingsDialog(
                    state = DeckUiState(),
                    onDisplaySettingsChange = {},
                    selectedLanguageTag = language.value,
                    onLanguageChange = { language.value = it },
                    selectedTranslationLanguageTag = translationLanguage.value,
                    onTranslationLanguageChange = { translationLanguage.value = it },
                    onExport = {}, onImport = {}, onDismiss = {},
                    transferStatus = TransferStatus.NONE,
                )
            }
        }
        for ((tag, selected) in listOf("setting-language" to language, "setting-translation-language" to translationLanguage)) {
            for (choice in AppLocaleController.supportedLanguageTags) {
                composeRule.onNodeWithTag("$tag-selector").performScrollTo().performClick()
                composeRule.onNodeWithTag("$tag-${selected.value}").assertIsSelected()
                composeRule.onNodeWithTag("$tag-$choice").performScrollTo().performClick()
                composeRule.onNodeWithTag("$tag-$choice").assertDoesNotExist()
                composeRule.runOnIdle { assertEquals(choice, selected.value) }
            }
            composeRule.onNodeWithTag("$tag-selector").performScrollTo().performClick()
            // Compose can be idle before WindowManager gives the newly opened popup focus.
            // Send Back only after it stops targeting the activity's window.
            composeRule.waitUntil(5_000) {
                val focus = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window")
                val line = android.os.ParcelFileDescriptor.AutoCloseInputStream(focus).bufferedReader().use {
                    it.readLines().firstOrNull { value -> value.contains("mCurrentFocus=") }.orEmpty()
                }
                line.isNotBlank() && !line.contains("null") && !line.contains("MainActivity")
            }
            val back = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent BACK")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(back).use { it.readBytes() }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("$tag-ur"))
                    .fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("$tag-ur").assertDoesNotExist()
            composeRule.runOnIdle { assertEquals("ur", selected.value) }
        }
    }

    @Test
    fun diagnosticsRefreshAndSettingsTransferRemainAvailable() {
        val state = mutableStateOf(DeckUiState(settingsConflict = true, xApiMetadataError = true))
        var refreshes = 0
        var exports = 0
        var imports = 0
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                SettingsDialog(
                    state = state.value,
                    onDisplaySettingsChange = {},
                    onExport = { exports++ },
                    onImport = { imports++ },
                    transferStatus = TransferStatus.FAILED,
                    onDismiss = {},
                    onRefreshXApiMetadata = { refreshes++ },
                )
            }
        }
        composeRule.onNodeWithTag("settings-save-conflict").assertIsDisplayed()
        composeRule.onNodeWithTag("translation-health").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live-pipeline-status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("x-api-metadata-status").performScrollTo().assertIsDisplayed()
        val refresh = composeRule.onNodeWithTag("refresh-x-api-metadata")
        refresh.performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { state.value = state.value.copy(xApiMetadataRefreshing = true) }
        refresh.assertIsNotEnabled().performClick()
        composeRule.runOnIdle { state.value = state.value.copy(xApiMetadataRefreshing = false) }
        refresh.assertIsEnabled().performClick()
        composeRule.onNodeWithTag("export-settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("import-settings").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(2, refreshes)
            assertEquals(1, exports)
            assertEquals(1, imports)
        }
    }
}
