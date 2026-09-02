package dev.nytweetdeck.android.ui

import androidx.compose.ui.unit.LayoutDirection
import dev.nytweetdeck.android.model.NavigationPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class MainMenuTest {
    @Test
    fun sideRevealAcceptsOnlyTheInwardDragForEachLayoutDirection() {
        assertEquals(
            24f,
            navigationRevealDelta(NavigationPosition.LEFT, LayoutDirection.Ltr, 24f, 0f),
            0.001f,
        )
        assertEquals(
            0f,
            navigationRevealDelta(NavigationPosition.LEFT, LayoutDirection.Ltr, -24f, 0f),
            0.001f,
        )
        assertEquals(
            24f,
            navigationRevealDelta(NavigationPosition.LEFT, LayoutDirection.Rtl, -24f, 0f),
            0.001f,
        )
        assertEquals(
            0f,
            navigationRevealDelta(NavigationPosition.LEFT, LayoutDirection.Rtl, 24f, 0f),
            0.001f,
        )
    }

    @Test
    fun bottomRevealAcceptsOnlyAnUpwardDrag() {
        assertEquals(
            24f,
            navigationRevealDelta(NavigationPosition.BOTTOM, LayoutDirection.Ltr, 0f, -24f),
            0.001f,
        )
        assertEquals(
            0f,
            navigationRevealDelta(NavigationPosition.BOTTOM, LayoutDirection.Ltr, 0f, 24f),
            0.001f,
        )
    }
}
