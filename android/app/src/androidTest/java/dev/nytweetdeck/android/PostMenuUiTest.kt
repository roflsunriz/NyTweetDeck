package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostMenuAction
import dev.nytweetdeck.android.ui.PostMenuDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PostMenuUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private var action: PostMenuAction? = null
    private var membership: Triple<String, String, Boolean>? = null

    @Before
    fun showMenu() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                PostMenuDialog(
                    post = post(),
                    onAction = { _, selected -> action = selected },
                    onListMembership = { selected, listId, add ->
                        membership = Triple(selected.id, listId, add)
                    },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun blockRequiresConfirmationOnAquos() {
        composeRule.onNodeWithTag("post-menu-action-block").performClick()
        composeRule.onNodeWithTag("block-confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-block").performClick()
        assertEquals(PostMenuAction.BLOCK, action)
    }

    @Test
    fun listIdIsValidatedOnAquos() {
        composeRule.onNodeWithTag("post-menu-list-membership").performClick()
        composeRule.onNodeWithTag("post-menu-list-id").performTextInput("12345")
        composeRule.onNodeWithTag("post-menu-list-add").performClick()
        assertEquals(Triple("1", "12345", true), membership)
    }

    private fun post() = Post(
        "1", "post", "ja", "2026-08-29T00:00:00Z",
        Author("7", "fixture", "Fixture", null, false), null, null,
        0, 0, 0, 0, 0, 0, false, false, false,
        null, null, null, null, null, null, null, emptyList(),
    )
}
