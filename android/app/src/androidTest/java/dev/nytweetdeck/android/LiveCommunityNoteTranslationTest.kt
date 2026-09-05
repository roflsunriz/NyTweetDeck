package dev.nytweetdeck.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.CommunityNoteRepository
import dev.nytweetdeck.android.model.CommunityNote
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.ui.TranslatableCommunityNote
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.xapi.XApiEnvironment
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in: noteId instrumentation argument; never prints session or note content. */
class LiveCommunityNoteTranslationTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun realXTranslationSwitchesWithoutLosingOriginal() {
        val noteId = InstrumentationRegistry.getArguments().getString("noteId").orEmpty()
        assumeTrue("Live test requires explicit noteId", Regex("[0-9]{1,24}").matches(noteId))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context.noBackupFilesDir.resolve("accounts/accounts.json"))
        val account = store.accountSecrets().firstOrNull { it.accountId == store.selectedAccountId() }
            ?: store.accountSecrets().first()
        val detail = CommunityNoteRepository(XApiEnvironment(context).graphQlClient()).loadNote(account, noteId, "ja")
        val translated = requireNotNull(detail.translation) { "X did not provide the requested translation" }
        assertTrue(translated.text.isNotBlank())
        assertTrue(translated.sources.all { it.fromIndex >= 0 && it.toIndex <= translated.text.length })
        val note = CommunityNote(null, detail.text, null, noteId, detail.language, detail.isTranslatable, detail.sources)
        var state by mutableStateOf(PostTranslationUiState(TranslationLoadStatus.READY, translated))
        composeRule.setContent { NyTweetDeckTheme {
            TranslatableCommunityNote(note, mapOf("note:$noteId" to state), true, {}, {},
                { state = state.copy(showOriginal = !state.showOriginal) })
        } }
        composeRule.onNodeWithTag("community-note-source").assertExists()
        composeRule.onNodeWithTag("note-translation-toggle").performClick()
        composeRule.runOnIdle { assertTrue(state.showOriginal) }
        composeRule.onNodeWithTag("note-translation-toggle").performClick()
        composeRule.runOnIdle { assertTrue(!state.showOriginal) }
    }
}
