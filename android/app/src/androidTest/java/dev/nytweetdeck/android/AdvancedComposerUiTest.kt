package dev.nytweetdeck.android

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.nytweetdeck.android.model.ComposerPlace
import dev.nytweetdeck.android.model.ComposerSubmission
import dev.nytweetdeck.android.model.ComposerUiState
import dev.nytweetdeck.android.ui.AdvancedComposerDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdvancedComposerUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pollEmojiAndDisclosuresReachTheSubmission() {
        var submitted: ComposerSubmission? = null
        composeRule.setContent {
            NyTweetDeckTheme {
                AdvancedComposerDialog(
                    state = ComposerUiState(),
                    onSubmit = { submitted = it },
                    onSearchPlaces = { emptyList() },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("composer-text").performTextInput("Question")
        composeRule.onNodeWithTag("composer-toggle-emoji").performClick()
        composeRule.onNodeWithTag("composer-toggle-poll").performClick()
        composeRule.onNodeWithTag("composer-choice-1").performScrollTo().performTextInput("Yes")
        composeRule.onNodeWithTag("composer-choice-2").performScrollTo().performTextInput("No")
        composeRule.onNodeWithTag("composer-choice-2").performImeAction()
        composeRule.onNodeWithTag("composer-duration-60").performScrollTo().performClick()
        composeRule.onNodeWithTag("composer-duration-60").assertIsSelected()
        composeRule.onNodeWithTag("composer-toggle-disclosure").performScrollTo().performClick()
        composeRule.onNodeWithTag("composer-paid-partnership").performScrollTo().performClick()
        composeRule.onNodeWithTag("composer-ai-generated").performScrollTo().performClick()
        composeRule.onNodeWithTag("send-composer").performScrollTo().performClick()

        assertNotNull(submitted)
        assertEquals("Question", submitted?.text)
        assertEquals(listOf("Yes", "No"), submitted?.poll?.choices)
        assertEquals(60, submitted?.poll?.durationMinutes)
        assertTrue(submitted?.paidPartnership == true)
        assertTrue(submitted?.aiGenerated == true)
    }

    @Test
    fun locationSearchAndMediaPickersAreReachableWithoutPublishing() {
        var submitted: ComposerSubmission? = null
        composeRule.setContent {
            NyTweetDeckTheme {
                AdvancedComposerDialog(
                    state = ComposerUiState(),
                    onSubmit = { submitted = it },
                    onSearchPlaces = { listOf(ComposerPlace("abc123", "Tokyo, Japan", "Japan")) },
                    onDismiss = {},
                )
            }
        }

        assertNotNull(composeRule.onNodeWithTag("composer-add-image").fetchSemanticsNode())
        assertNotNull(composeRule.onNodeWithTag("composer-add-gif").fetchSemanticsNode())
        assertNotNull(composeRule.onNodeWithTag("composer-toggle-schedule").fetchSemanticsNode())
        composeRule.onNodeWithTag("composer-text").performTextInput("From Tokyo")
        composeRule.onNodeWithTag("composer-toggle-place").performScrollTo().performClick()
        composeRule.onNodeWithTag("composer-place-query").performScrollTo().performTextInput("Tokyo")
        composeRule.onNodeWithTag("composer-place-search").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("composer-place-option-abc123").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("composer-place-option-abc123").performScrollTo().performClick()
        composeRule.onNodeWithTag("send-composer").performScrollTo().performClick()

        assertEquals("abc123", submitted?.place?.id)
        assertFalse(submitted?.paidPartnership == true)
    }
}
