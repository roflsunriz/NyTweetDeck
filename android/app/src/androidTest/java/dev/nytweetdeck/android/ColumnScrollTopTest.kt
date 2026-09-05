package dev.nytweetdeck.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.model.*
import dev.nytweetdeck.android.ui.DeckContent
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ColumnScrollTopTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun jumpsOnlyTheSelectedColumnToTopWithoutRefreshingOrChangingSort() {
        val columns = listOf(
            DeckColumn("first", ColumnKind.HOME_FOR_YOU, "First", sort = ColumnSort.LATEST),
            DeckColumn("second", ColumnKind.HOME_FOLLOWING, "Second", sort = ColumnSort.TOP),
        )
        val positions = mutableMapOf<String, Int>()
        var refreshes = 0
        var sortChanges = 0
        val posts = (1..50).map { id ->
            Post(id.toString(), "Post $id", "ja", null, Author("1", "user", "User", null, false),
                null, null, 0, 0, 0, 0, 0, 0, false, false, false,
                null, null, null, null, null, null, null, emptyList())
        }
        val state = DeckUiState(
            selectedAccountId = "1", columns = columns, reduceMotion = true,
            timelines = columns.associate { it.id to ColumnTimelineState(TimelineLoadStatus.READY, posts) },
        )
        composeRule.setContent {
            NyTweetDeckTheme {
                Box(Modifier.width(360.dp)) {
                    DeckContent(
                        state = state,
                        columnScrollPositions = emptyMap(),
                        onRemoveColumn = {}, onAddColumn = {}, onOpenAccounts = {},
                        onRefreshColumn = { refreshes++ }, onLoadMoreColumn = {},
                        onClearNewPostsColumn = {}, onVisibleColumnsChanged = {},
                        onMoveColumn = { _, _ -> },
                        onSaveColumnScrollPosition = { id, index, _, _ -> positions[id] = index },
                        onColumnSortChange = { _, _ -> sortChanges++ },
                    )
                }
            }
        }
        composeRule.onNode(hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-first")))
            .performScrollToKey("30")
        composeRule.waitForIdle()
        val firstScrolled = positions.getValue("first")
        org.junit.Assert.assertTrue(firstScrolled > 0)
        composeRule.onNodeWithTag("deck-columns").performScrollToKey("second")
        composeRule.onNode(hasTestTag("timeline-posts") and hasAnyAncestor(hasTestTag("column-second")))
            .performScrollToKey("20")
        composeRule.onNodeWithTag("column-scroll-top-second").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(0, positions["second"])
        assertEquals(firstScrolled, positions["first"])
        composeRule.onNodeWithTag("column-scroll-top-second").performClick()
        composeRule.onNodeWithTag("deck-columns").performScrollToKey("first")
        composeRule.onNodeWithTag("column-scroll-top-first").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(0, positions["first"])
        assertEquals(0, refreshes)
        assertEquals(0, sortChanges)
    }
}
