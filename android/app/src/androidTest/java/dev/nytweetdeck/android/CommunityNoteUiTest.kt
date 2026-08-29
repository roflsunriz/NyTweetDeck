package dev.nytweetdeck.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.CommunityNoteDetail
import dev.nytweetdeck.android.model.CommunityNotePage
import dev.nytweetdeck.android.model.CommunityNoteSource
import dev.nytweetdeck.android.model.CommunityNoteStatus
import dev.nytweetdeck.android.model.CommunityNoteUiState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.ui.CommunityNoteDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CommunityNoteUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private var opened: Intent? = null

    @Before
    fun showNote() {
        val text = "出典を確認できる完全なコミュニティノート本文"
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                CommunityNoteDialog(
                    state = CommunityNoteUiState(
                        CommunityNoteStatus.READY,
                        "55",
                        CommunityNotePage(
                            CommunityNoteDetail(
                                "55",
                                text,
                                listOf(CommunityNoteSource(0, text.length, "https://example.com/source")),
                                "123",
                            ),
                            post(),
                        ),
                    ),
                    onDismiss = {},
                    onRetry = {},
                    onOpenSource = { opened = it },
                )
            }
        }
    }

    @Test
    fun completeNotePostAndVerifiedSourceAreVisibleOnAquos() {
        composeRule.onNodeWithTag("community-note").assertIsDisplayed()
        composeRule.onAllNodesWithTag("community-note-source", useUnmergedTree = true)[0]
            .performClick()
        assertEquals("https://example.com/source", opened?.dataString)
        composeRule.onNodeWithTag("community-note-close").performClick()
    }

    private fun post() = Post(
        "123", "対象ポスト", "ja", "2026-08-29T00:00:00Z",
        Author("7", "fixture", "Fixture", null, false), null, null,
        0, 0, 0, 0, 0, 0, false, false, false,
        null, null, null, null, null, null, null, emptyList(),
    )
}
