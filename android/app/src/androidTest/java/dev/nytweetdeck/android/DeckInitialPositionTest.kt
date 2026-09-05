package dev.nytweetdeck.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.ui.DeckContent
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Rule
import org.junit.Test

class DeckInitialPositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun restoredColumnsStartAtFirstAndExplicitNavigationStillWorks() {
        val columns = listOf(
            DeckColumn("first", ColumnKind.NOTIFICATIONS, "First"),
            DeckColumn("second", ColumnKind.HOME_FOR_YOU, "Second"),
            DeckColumn("third", ColumnKind.HOME_FOR_YOU, "Third"),
        )
        val state = mutableStateOf(DeckUiState(isInitializing = true, reduceMotion = true))
        composeRule.setContent {
            NyTweetDeckTheme {
                Box(Modifier.width(320.dp)) {
                    DeckContent(
                        state = state.value,
                        columnScrollPositions = emptyMap(),
                        onRemoveColumn = {},
                        onAddColumn = {},
                        onOpenAccounts = {},
                        onRefreshColumn = {},
                        onLoadMoreColumn = {},
                        onClearNewPostsColumn = {},
                        onVisibleColumnsChanged = {},
                        onMoveColumn = { _, _ -> },
                        onSaveColumnScrollPosition = { _, _, _, _ -> },
                    )
                }
            }
        }
        composeRule.runOnIdle {
            state.value = state.value.copy(isInitializing = false, columns = columns)
        }
        composeRule.onNodeWithTag("column-first").assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.copy(liveConnected = true) }
        composeRule.onNodeWithTag("column-first").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(selectedMenu = ColumnKind.NOTIFICATIONS)
        }
        composeRule.runOnIdle {
            state.value = state.value.copy(selectedMenu = ColumnKind.HOME_FOR_YOU)
        }
        composeRule.onNodeWithTag("column-third").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                columns = columns + DeckColumn("fourth", ColumnKind.HOME_FOR_YOU, "Fourth"),
            )
        }
        composeRule.onNodeWithTag("column-fourth").assertIsDisplayed()
    }
}
