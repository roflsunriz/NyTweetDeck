package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.nytweetdeck.android.model.ListOption
import dev.nytweetdeck.android.model.ListPickerScope
import dev.nytweetdeck.android.model.ListPickerState
import dev.nytweetdeck.android.model.TargetPickerState
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.ui.AddColumnDialog
import dev.nytweetdeck.android.ui.ListPickerDialog
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ListPickerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun listChoiceLeavesTheAddColumnDialogAndOpensDedicatedPicker() {
        var requested = false
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                AddColumnDialog(
                    pickerState = TargetPickerState(),
                    onDismiss = {},
                    onAdd = { _, _, _ -> },
                    onResolveUser = {},
                    onOpenLists = { requested = true },
                )
            }
        }

        composeRule.onNodeWithTag("add-list").performClick()
        assertTrue(requested)
        composeRule.onNodeWithTag("list-picker-dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("column-target").assertDoesNotExist()
    }

    @Test
    fun dedicatedPickerSwitchesScopesAndSelectsCachedOptionsImmediately() {
        var selectedScope: ListPickerScope? = null
        var selectedList: ListOption? = null
        val mine = option("mine", "Mine", "mine")
        val suggested = option("suggested", "Suggested", "suggested")
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                ListPickerDialog(
                    state = ListPickerState(
                        status = TimelineLoadStatus.READY,
                        selectedScope = ListPickerScope.MINE,
                        mineOptions = listOf(mine),
                        suggestedOptions = listOf(suggested),
                        isRefreshing = true,
                    ),
                    onScopeChange = { selectedScope = it },
                    onSearch = {},
                    onSelect = { selectedList = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("list-picker-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("list-option-mine").assertIsDisplayed().performClick()
        assertEquals(mine, selectedList)
        composeRule.onNodeWithTag("list-scope-suggested").performClick()
        assertEquals(ListPickerScope.SUGGESTED, selectedScope)
        composeRule.onNodeWithTag("list-picker-refreshing").assertIsDisplayed()
    }

    private fun option(id: String, name: String, source: String) = ListOption(
        id, name, null, null, null, 1, 2, source,
    )
}
