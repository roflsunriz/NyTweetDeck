package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.nytweetdeck.android.model.*
import dev.nytweetdeck.android.ui.*
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OpenIssueFeaturesUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun removedColumnCanBeRestoredFromTheActualDeck() {
        val model = DeckViewModel()
        model.addColumn(ColumnKind.HISTORY, "History")
        val id = model.state.value.columns.single().id
        composeRule.activity.setContent { NyTweetDeckApp(providedViewModel = model) }
        composeRule.onNodeWithTag("remove-column").performClick()
        composeRule.onNodeWithTag("column-$id").assertDoesNotExist()
        composeRule.onNodeWithTag("column-restore-$id").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("column-$id").assertIsDisplayed()
        composeRule.onNodeWithTag("column-restore-$id").assertDoesNotExist()
    }

    @Test fun shareMenuCopiesMixedMediaAndCanOmitQuote() {
        var copied: String? = null
        val post = TimelineResponseParser().parse("""{"data":{"tweet":{"__typename":"Tweet","rest_id":"123",
            "legacy":{"full_text":"original body"},"core":{"user_results":{"result":{"__typename":"User",
            "rest_id":"7","core":{"screen_name":"fixture","name":"Fixture"}}}}}}}""").posts.single()
        val media = listOf(Media("p", "photo", "https://pbs.twimg.com/p.jpg", null),
            Media("v", "video", "https://video.twimg.com/v.mp4", null))
        val quote = EmbeddedPost("122", "quoted body", "en", null, post.author, null, null, emptyList())
        composeRule.activity.setContent {
            NyTweetDeckTheme { PostCard(post = post.copy(media = media, quotedPost = quote),
                onShareDetailsCopy = { copied = it }) }
        }
        composeRule.onNodeWithTag("post-action-share-123").performClick()
        composeRule.onNodeWithTag("share-menu-media-123").performClick()
        composeRule.runOnIdle { assertEquals("https://pbs.twimg.com/p.jpg\nhttps://video.twimg.com/v.mp4", copied) }
        composeRule.onNodeWithTag("post-action-share-123").performClick()
        composeRule.onNodeWithTag("share-menu-details-without-quote-123").performClick()
        composeRule.runOnIdle {
            assertTrue(copied!!.contains("original body"))
            assertFalse(copied!!.contains("quoted body"))
            assertTrue(copied!!.endsWith("https://x.com/i/status/123"))
        }
    }
}
