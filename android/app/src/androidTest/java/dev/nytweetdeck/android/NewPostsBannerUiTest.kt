package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.nytweetdeck.android.ui.NewPostsBanner
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NewPostsBannerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun newPostNoticeRemainsAnIndependentClickableOverlay() {
        var clicked = false
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                NewPostsBanner(count = 3, avatarUrls = emptyList(), onClick = { clicked = true })
            }
        }

        composeRule.onNodeWithTag("new-posts-banner").assertIsDisplayed().performClick()
        assertTrue(clicked)
    }
}
