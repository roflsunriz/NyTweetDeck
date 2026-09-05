package dev.nytweetdeck.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.nytweetdeck.android.model.CommunityNote
import dev.nytweetdeck.android.model.CommunityNoteSource
import dev.nytweetdeck.android.model.PostTranslation
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.ui.TranslatableCommunityNote
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CommunityNoteTranslationUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val note = CommunityNote(null, "Original source", null, "55", "en", true,
        listOf(CommunityNoteSource(9, 15, "https://example.com/original")))

    @Test fun switchesTranslationAndOriginalAndKeepsTranslatedSource() {
        var state by mutableStateOf(PostTranslationUiState(TranslationLoadStatus.READY,
            PostTranslation("note:55", "en", "ja", "翻訳 出典", sources = listOf(CommunityNoteSource(3, 5, "https://example.com/translated")))))
        composeRule.setContent { NyTweetDeckTheme {
            TranslatableCommunityNote(note, mapOf("note:55" to state), true, {}, {},
                { state = state.copy(showOriginal = !state.showOriginal) })
        } }
        composeRule.onNodeWithText("翻訳 出典").assertExists()
        composeRule.onNodeWithTag("note-translation-toggle").performClick()
        composeRule.onNodeWithText("Original source").assertExists()
        composeRule.onNodeWithTag("note-translation-toggle").performClick()
        composeRule.onNodeWithText("翻訳 出典").assertExists()
    }

    @Test fun unavailableCanRetry() {
        var requests = 0
        var retries = 0
        composeRule.setContent { NyTweetDeckTheme {
            TranslatableCommunityNote(note, mapOf("note:55" to PostTranslationUiState(
                TranslationLoadStatus.FAILED, unavailable = true)), true, { requests++ }, { retries++ }, {})
        } }
        composeRule.onNodeWithText("Original source").assertExists()
        composeRule.onNodeWithTag("note-translation-retry").performClick()
        composeRule.runOnIdle { assertEquals(0, requests); assertEquals(1, retries) }
    }

    @Test fun disablingTranslationRestoresOriginalAndReenablingUsesTranslation() {
        var enabled by mutableStateOf(true)
        val state = PostTranslationUiState(TranslationLoadStatus.READY,
            PostTranslation("note:55", "en", "ja", "翻訳 出典"))
        var requests = 0
        composeRule.setContent { NyTweetDeckTheme {
            TranslatableCommunityNote(note, mapOf("note:55" to state), enabled, { requests++ }, {}, {})
        } }
        composeRule.onNodeWithText("翻訳 出典").assertExists()
        composeRule.runOnIdle { enabled = false }
        composeRule.onNodeWithText("Original source").assertExists()
        composeRule.onNodeWithTag("note-translation-toggle").assertDoesNotExist()
        composeRule.runOnIdle { enabled = true }
        composeRule.onNodeWithText("翻訳 出典").assertExists()
        composeRule.runOnIdle { assertEquals(0, requests) }
    }
}
