package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.*
import org.junit.Assert.*
import org.junit.Test

class ColumnRemovalControllerTest {
    private val columns = (1..3).map { DeckColumn("$it", ColumnKind.HISTORY, "column-$it") }
    private val initial = DeckUiState(selectedAccountId = "7", columns = columns,
        timelines = mapOf("2" to ColumnTimelineState(TimelineLoadStatus.READY, nextCursor = "cursor")),
        columnScrollPositions = mapOf("2" to ColumnScrollPosition("p", 4, 80)))

    @Test fun restoresOriginalPositionRuntimeAndScrollWithoutDiscardingLaterChanges() {
        val controller = ColumnRemovalController { 0L }
        val deleted = controller.remove(initial, "2")
        assertEquals(listOf("1", "3"), deleted.columns.map { it.id })
        assertFalse(deleted.timelines.containsKey("2"))
        val added = DeckColumn("4", ColumnKind.TRENDS, "new")
        val restored = controller.restore(deleted.copy(columns = deleted.columns + added), "2")
        assertEquals(listOf("1", "2", "3", "4"), restored.columns.map { it.id })
        assertEquals(initial.timelines, restored.timelines)
        assertEquals(initial.columnScrollPositions, restored.columnScrollPositions)
        assertTrue(restored.removedColumns.isEmpty())
        assertEquals(restored, controller.restore(restored, "2"))
    }

    @Test fun expiresAtTenSecondsAndNeverRestoresIntoAnotherAccount() {
        var time = 0L
        val controller = ColumnRemovalController { time }
        val deleted = controller.remove(initial, "2")
        time = 10_000
        assertEquals(listOf("1", "3"), controller.restore(deleted, "2").columns.map { it.id })
        time = 20_000
        val again = controller.remove(initial, "2")
        val other = again.copy(selectedAccountId = "8")
        assertEquals(other.columns, controller.restore(other, "2").columns)
    }

    @Test fun repeatedDeletionsHaveIndependentUndoEntries() {
        val controller = ColumnRemovalController { 0L }
        val deleted = controller.remove(controller.remove(initial, "2"), "3")
        assertEquals(2, deleted.removedColumns.size)
        val restored = controller.restore(controller.restore(deleted, "2"), "3")
        assertEquals(columns, restored.columns)
    }
}
