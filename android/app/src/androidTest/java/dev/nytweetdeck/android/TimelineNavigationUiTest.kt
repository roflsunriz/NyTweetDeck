package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.nytweetdeck.android.model.*
import dev.nytweetdeck.android.ui.DeckContent
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TimelineNavigationUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun newPostsTapJumpsToTopImmediatelyAndTopButtonClearsBanner() {
        val deck = mutableStateOf(timelineState())
        var clears = 0
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                DeckContent(
                    state = deck.value,
                    columnScrollPositions = mapOf("a" to ColumnScrollPosition(firstVisibleItemKey = "120")),
                    onRemoveColumn = {}, onAddColumn = {}, onOpenAccounts = {}, onRefreshColumn = {},
                    onLoadMoreColumn = {}, onVisibleColumnsChanged = {}, onMoveColumn = { _, _ -> },
                    onSaveColumnScrollPosition = { _, _, _, _ -> },
                    onClearNewPostsColumn = {
                        clears++
                        deck.value = deck.value.copy(timelines = deck.value.timelines.mapValues { (_, value) ->
                            value.copy(newPostCount = 0, newPostAvatarUrls = emptyList())
                        })
                    }, autoTranslatePosts = false,
                )
            }
        }
        composeRule.onNodeWithTag("new-posts-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("new-posts-banner").performClick()
        composeRule.onNodeWithTag("post-100").assertIsDisplayed()
        composeRule.onNodeWithTag("new-posts-banner").assertDoesNotExist()
        composeRule.onNodeWithTag("timeline-posts").performScrollToIndex(20)
        composeRule.runOnIdle { deck.value = timelineState() }
        composeRule.onNodeWithTag("new-posts-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("column-scroll-top-a").performClick()
        composeRule.onNodeWithTag("post-100").assertIsDisplayed()
        composeRule.onNodeWithTag("new-posts-banner").assertDoesNotExist()
        assertEquals(2, clears)
        // Returning by ordinary vertical scroll also acknowledges the new posts.
        composeRule.onNodeWithTag("timeline-posts").performScrollToIndex(20)
        composeRule.runOnIdle { deck.value = timelineState() }
        composeRule.onNodeWithTag("timeline-posts").performScrollToIndex(0)
        composeRule.onNodeWithTag("new-posts-banner").assertDoesNotExist()
        assertEquals(3, clears)
    }

    @Test
    fun horizontalLockPreservesVerticalScrollingAndExplicitColumnSelection() {
        val deck = timelineState().copy(
            lockColumnSwipe = true,
            columns = listOf(DeckColumn("a", ColumnKind.HOME_FOR_YOU, "A"), DeckColumn("b", ColumnKind.HOME_FOLLOWING, "B")),
        )
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                DeckContent(
                    state = deck, columnScrollPositions = emptyMap(),
                    onRemoveColumn = {}, onAddColumn = {}, onOpenAccounts = {}, onRefreshColumn = {},
                    onLoadMoreColumn = {}, onClearNewPostsColumn = {}, onVisibleColumnsChanged = {},
                    onMoveColumn = { _, _ -> }, onSaveColumnScrollPosition = { _, _, _, _ -> },
                    autoTranslatePosts = false,
                )
            }
        }
        composeRule.onNodeWithTag("column-a").assertIsDisplayed()
        composeRule.onNodeWithTag("deck-columns").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("column-a").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-posts").performScrollToIndex(20)
        composeRule.onNodeWithTag("post-120").assertIsDisplayed()
        composeRule.onNodeWithTag("locked-column-selector").performClick()
        composeRule.onNodeWithTag("locked-column-select-b").performClick()
        composeRule.onNodeWithTag("column-b").assertIsDisplayed()
    }

    private fun timelineState(): DeckUiState {
        val posts = (100..150).map { id ->
            TimelineResponseParser().parse("""{"tweet":{"rest_id":"$id","legacy":{"full_text":"fixture $id","lang":"en"}}}""").posts.single()
        }
        return DeckUiState(
            selectedAccountId = "7", columns = listOf(DeckColumn("a", ColumnKind.HOME_FOR_YOU, "A")),
            timelines = mapOf("a" to ColumnTimelineState(status = TimelineLoadStatus.READY, posts = posts, newPostCount = 3)),
        )
    }
}
