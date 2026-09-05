package dev.nytweetdeck.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.DefaultMainMenuItems
import dev.nytweetdeck.android.model.NavigationPosition
import dev.nytweetdeck.android.model.ThemeMode
import dev.nytweetdeck.android.model.AppFontSize
import dev.nytweetdeck.android.model.AccentColor
import dev.nytweetdeck.android.model.ColumnSort
import dev.nytweetdeck.android.model.VideoQuality
import androidx.test.espresso.intent.Intents
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import org.hamcrest.Matchers.allOf
import android.app.Instrumentation.ActivityResult
import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import androidx.activity.compose.setContent
import dev.nytweetdeck.android.ui.DeckViewModel
import dev.nytweetdeck.android.ui.NyTweetDeckApp
import dev.nytweetdeck.android.ui.displaySettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DeckUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var isolatedViewModel: DeckViewModel

    @Before
    fun useIsolatedInMemoryState() {
        isolatedViewModel = DeckViewModel()
        composeRule.activity.setContent {
            NyTweetDeckApp(providedViewModel = isolatedViewModel)
        }
    }

    @Test
    fun emptyDeckCanAddAndRemoveHomeColumn() {
        composeRule.onNodeWithTag("empty-deck").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("add-home_for_you").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("remove-column").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("empty-deck").assertIsDisplayed()
    }

    @Test
    fun settingsCanChangeThemeAndDensity() {
        composeRule.onNodeWithTag("settings").performClick()
        composeRule.onNodeWithTag("setting-theme-light").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-accent-purple").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-font-large").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-compact-density").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("setting-reduce-motion").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-media-preview").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-video-autoplay").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-auto-refresh").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-video-loop").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-auto-translate").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-translation-language-selector").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-translation-language-fr").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-video-quality-low").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-navigation-bottom").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-show-main-navigation").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting-video-volume").performScrollTo().performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { it(42f) }
        composeRule.onNodeWithTag("download-latest-apk").performScrollTo().assertIsDisplayed()

        val state = isolatedViewModel.state.value
        assertEquals(ThemeMode.LIGHT, state.themeMode)
        assertEquals(AppFontSize.LARGE, state.fontSize)
        assertEquals(AccentColor.PURPLE, state.accentColor)
        assertEquals(true, state.compactDensity)
        assertEquals(true, state.reduceMotion)
        assertEquals(false, state.mediaPreview)
        assertEquals(true, state.videoAutoplay)
        assertEquals(false, state.videoLoop)
        assertEquals(42, state.videoVolume)
        assertEquals(false, state.autoTranslatePosts)
        assertEquals(false, state.autoRefreshTimelines)
        assertEquals("fr", state.translationLanguageTag)
        assertEquals(VideoQuality.LOW, state.videoQuality)
        assertEquals(NavigationPosition.BOTTOM, state.navigationPosition)
        assertEquals(false, state.showMainNavigation)
        if (InstrumentationRegistry.getArguments().getString("capture") == "true") {
            Thread.sleep(5_000)
        }
    }

    @Test
    fun autoHiddenMainMenuRevealsFromTheLeftEdgeAndHidesAfterThreeSeconds() {
        isolatedViewModel.setDisplaySettings(
            isolatedViewModel.state.value.displaySettings().copy(showMainNavigation = false),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings").assertDoesNotExist()
        composeRule.onNodeWithTag("navigation-swipe-edge").assertIsDisplayed().performTouchInput {
            val start = center
            down(start)
            moveTo(Offset(start.x + 240f, start.y), 300)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(3_100)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings").assertDoesNotExist()
        assertEquals(false, isolatedViewModel.state.value.showMainNavigation)

        isolatedViewModel.setDisplaySettings(
            isolatedViewModel.state.value.displaySettings().copy(showMainNavigation = true),
        )
        composeRule.mainClock.advanceTimeBy(3_100)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings").assertIsDisplayed()
    }

    @Test
    fun bottomMainMenuUsesTheBottomEdgeSwipeWhenAutoHidden() {
        isolatedViewModel.setDisplaySettings(
            isolatedViewModel.state.value.displaySettings().copy(
                navigationPosition = NavigationPosition.BOTTOM,
                showMainNavigation = true,
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("main-menu-bottom").assertIsDisplayed()
        composeRule.onNodeWithTag("main-menu-left").assertDoesNotExist()

        isolatedViewModel.setDisplaySettings(
            isolatedViewModel.state.value.displaySettings().copy(showMainNavigation = false),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("main-menu-bottom").assertDoesNotExist()
        composeRule.onNodeWithTag("navigation-swipe-edge").performTouchInput {
            val start = center
            down(start)
            moveTo(Offset(start.x, start.y - 240f), 300)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("main-menu-bottom").assertIsDisplayed()
    }

    @Test
    fun timelineColumnCanSwitchBetweenLatestAndTop() {
        isolatedViewModel.addColumn(ColumnKind.HOME_FOR_YOU, "Home")
        val columnId = isolatedViewModel.state.value.columns.single().id

        composeRule.onNodeWithTag("column-sort-top-$columnId").performClick()

        assertEquals(ColumnSort.TOP, isolatedViewModel.state.value.columns.single().sort)
    }

    @Test
    fun allNineColumnKindsCanBeAddedWithRequiredTargets() {
        listOf(
            "home_for_you" to null,
            "home_following" to null,
            "notifications" to null,
            "messages" to null,
            "trends" to null,
            "history" to null,
            "search" to "NyTweetDeck",
            "user" to "12345",
            "list" to "67890",
        ).forEach { (kind, target) ->
            if (kind == "user" || kind == "list") {
                isolatedViewModel.addColumn(
                    if (kind == "user") ColumnKind.USER else ColumnKind.LIST,
                    if (kind == "user") "User" else "List",
                    requireNotNull(target),
                )
                return@forEach
            }
            composeRule.onNodeWithTag("menu-search").performClick()
            composeRule.onNodeWithTag("add-$kind").performScrollTo().performClick()
            if (target != null) {
                composeRule.onNodeWithTag("column-target").performTextInput(target)
                composeRule.onNodeWithTag("confirm-target-column").performClick()
            }
        }

        assertEquals(9, isolatedViewModel.state.value.columns.size)
        assertEquals("NyTweetDeck", isolatedViewModel.state.value.columns
            .single { it.kind == ColumnKind.SEARCH }.target)
        assertEquals("12345", isolatedViewModel.state.value.columns
            .single { it.kind == ColumnKind.USER }.target)
        assertEquals("67890", isolatedViewModel.state.value.columns
            .single { it.kind == ColumnKind.LIST }.target)
    }

    @Test
    fun longPressHorizontalDragReordersColumns() {
        isolatedViewModel.addColumn(ColumnKind.HOME_FOR_YOU, "Home")
        isolatedViewModel.addColumn(ColumnKind.NOTIFICATIONS, "Notifications")
        composeRule.waitForIdle()
        val firstId = isolatedViewModel.state.value.columns.first().id
        composeRule.onNodeWithTag("menu-home").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("column-$firstId").performTouchInput {
            val start = center
            down(start)
            advanceEventTime(650)
            moveTo(Offset(start.x + (right - left) * 0.28f, start.y), 120)
            moveTo(Offset(right - 2f, start.y), 200)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(firstId, isolatedViewModel.state.value.columns.last().id)
    }

    @Test
    fun optionalMenuItemsCanBeAddedRemovedAndReorderedByLongPress() {
        composeRule.onNodeWithTag("edit-menu").performClick()
        composeRule.onNodeWithTag("menu-option-chat").performScrollTo().performClick()
        assertEquals(true, MainMenuItemId.CHAT in isolatedViewModel.state.value.mainMenuItems)
        composeRule.onNodeWithTag("menu-option-chat").performClick()
        assertEquals(false, MainMenuItemId.CHAT in isolatedViewModel.state.value.mainMenuItems)

        val before = isolatedViewModel.state.value.mainMenuItems.indexOf(MainMenuItemId.HOME)
        composeRule.onNodeWithTag("menu-option-home").performScrollTo().performTouchInput {
            val start = center
            down(start)
            advanceEventTime(650)
            moveTo(Offset(start.x, start.y - (bottom - top) * 0.7f), 160)
            moveTo(Offset(start.x, top + 2f), 160)
            up()
        }
        composeRule.waitForIdle()
        val after = isolatedViewModel.state.value.mainMenuItems.indexOf(MainMenuItemId.HOME)
        assertEquals(before - 1, after)
    }

    @Test
    fun allOptionalItemsAndInternalExternalNavigationAreReachable() {
        assertEquals(DefaultMainMenuItems, isolatedViewModel.state.value.mainMenuItems)
        composeRule.onNodeWithTag("edit-menu").performClick()
        MainMenuItemId.entries.filterNot { it in DefaultMainMenuItems }.forEach { id ->
            composeRule.onNodeWithTag("menu-option-${id.name.lowercase()}")
                .performScrollTo()
                .performClick()
        }
        assertEquals(MainMenuItemId.entries.size, isolatedViewModel.state.value.mainMenuItems.size)
        composeRule.onNodeWithTag("close-menu-editor").performClick()

        composeRule.onNodeWithTag("menu-compose").performScrollTo().performClick()
        composeRule.onNodeWithTag("login-x").assertIsDisplayed()
        composeRule.onNodeWithTag("close-accounts").performClick()

        val beforeFollowing = isolatedViewModel.state.value.columns.size
        composeRule.onNodeWithTag("menu-following").performScrollTo().performClick()
        assertEquals(beforeFollowing + 1, isolatedViewModel.state.value.columns.size)

        composeRule.onNodeWithTag("menu-profile").performScrollTo().performClick()
        composeRule.onNodeWithTag("login-x").assertIsDisplayed()
        composeRule.onNodeWithTag("close-accounts").performClick()

        Intents.init()
        try {
            val matcher = allOf(
                hasAction(Intent.ACTION_VIEW),
                hasData("https://x.com/i/chat"),
            )
            intending(matcher).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            composeRule.onNodeWithTag("menu-chat").performScrollTo().performClick()
            intended(matcher)
        } finally {
            Intents.release()
        }
    }
}
