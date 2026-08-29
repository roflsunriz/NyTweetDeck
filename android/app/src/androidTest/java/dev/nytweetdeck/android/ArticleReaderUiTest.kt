package dev.nytweetdeck.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.ArticleReaderStatus
import dev.nytweetdeck.android.model.ArticleReaderUiState
import dev.nytweetdeck.android.ui.ArticleReaderDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ArticleReaderUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private var openedIntent: Intent? = null
    private var closed = false

    @Before
    fun showArticle() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                ArticleReaderDialog(
                    state = ArticleReaderUiState(
                        ArticleReaderStatus.READY,
                        "700",
                        Article(
                            "701",
                            "記事タイトル",
                            "概要",
                            "記事全文です。\n\n続きの段落です。",
                            null,
                            "https://x.com/i/article/701",
                        ),
                    ),
                    onDismiss = { closed = true },
                    onRetry = {},
                    onOpenX = { openedIntent = it },
                )
            }
        }
    }

    @Test
    fun articleBodyExternalIntentAndCloseAreReachableOnAquos() {
        composeRule.onNodeWithTag("article-reader").assertIsDisplayed()
        composeRule.onNodeWithTag("article-reader-open-x").performClick()
        assertEquals("https://x.com/i/article/701", openedIntent?.dataString)
        composeRule.onNodeWithTag("article-reader-close").performClick()
        assertEquals(true, closed)
    }
}
