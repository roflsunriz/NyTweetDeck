package dev.nytweetdeck.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.EmbeddedPost
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.ui.PostCard
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TranslationGuardUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun mediaMentionsAndQuotesNeverRequestTranslationOrShowStaleFailure() {
        val requested = mutableListOf<String>()
        val image = Media("image", "photo", "https://pbs.twimg.com/media/fixture.jpg", null)
        val empty = post("123", "@alice https://t.co/photo").copy(
            media = listOf(image),
            quotedPost = EmbeddedPost("124", "＠bob", "en", null, author, null, null, listOf(image)),
        )
        composeRule.setContent {
            NyTweetDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PostCard(
                        post = empty, mediaPreview = false,
                        translationStates = mapOf(
                            "123" to PostTranslationUiState(TranslationLoadStatus.FAILED),
                            "124" to PostTranslationUiState(TranslationLoadStatus.FAILED),
                        ),
                        onTranslationNeeded = { requested += it.postId },
                    )
                    PostCard(post = post("125", "@alice https://t.co/photo　Hello world!"),
                        onTranslationNeeded = { requested += it.postId })
                    PostCard(post = post("126", ""), mediaPreview = false,
                        onTranslationNeeded = { requested += it.postId })
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("125"), requested)
        composeRule.onNodeWithTag("translation-retry").assertDoesNotExist()
        composeRule.onNodeWithTag("translation-loading").assertDoesNotExist()
        composeRule.onNodeWithTag("translation-toggle").assertDoesNotExist()
    }

    private val author = Author("7", "user", "User", null, false)
    private fun post(id: String, text: String) = Post(
        id, text, "en", null, author, null, null, 0, 0, 0, 0, 0, 0,
        false, false, false, null, null, null, null, null, null, null, emptyList(),
    )
}
