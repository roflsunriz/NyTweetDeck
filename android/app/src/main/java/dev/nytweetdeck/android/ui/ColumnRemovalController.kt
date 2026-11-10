package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.*

/** Undo entries keep runtime content for ten seconds; they never enter the persisted layout. */
internal class ColumnRemovalController(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Removed(
        val accountId: String?, val column: DeckColumn, val index: Int, val deadline: Long,
        val order: List<String>, val timeline: ColumnTimelineState?, val notifications: NotificationColumnState?,
        val trends: TrendColumnState?, val messages: DirectMessageColumnState?, val scroll: ColumnScrollPosition?,
    )
    private val removed = LinkedHashMap<String, Removed>()

    fun remove(state: DeckUiState, id: String): DeckUiState {
        val index = state.columns.indexOfFirst { it.id == id }
        if (index < 0) return state
        prune()
        val deadline = now() + 10_000
        val order = removed.values.firstOrNull()?.order.orEmpty().toMutableList()
        state.columns.forEach { if (it.id !in order) order.add(it.id) }
        removed[id] = Removed(state.selectedAccountId, state.columns[index], index, deadline, order,
            state.timelines[id], state.notifications[id], state.trends[id], state.messages[id], state.columnScrollPositions[id])
        return state.copy(
            columns = state.columns.filterNot { it.id == id },
            timelines = state.timelines - id,
            notifications = state.notifications - id,
            trends = state.trends - id,
            messages = state.messages - id,
            columnScrollPositions = state.columnScrollPositions - id,
            removedColumns = state.removedColumns.filter { it.id in removed } +
                RemovedColumnNotice(id, state.columns[index].title, deadline),
        )
    }

    fun restore(state: DeckUiState, id: String): DeckUiState {
        prune()
        val entry = removed.remove(id) ?: return expire(state, id)
        if (entry.accountId != state.selectedAccountId || state.columns.any { it.id == id }) {
            return expire(state, id)
        }
        val columns = state.columns.toMutableList()
        val originalColumn = entry.column
        val orderIndex = entry.order.indexOf(id)
        val nextId = entry.order.drop(orderIndex + 1).firstOrNull { candidate -> columns.any { it.id == candidate } }
        val previousId = entry.order.take(orderIndex).lastOrNull { candidate -> columns.any { it.id == candidate } }
        val insertion = nextId?.let { next -> columns.indexOfFirst { it.id == next } }
            ?: previousId?.let { previous -> columns.indexOfFirst { it.id == previous } + 1 }
            ?: entry.index.coerceAtMost(columns.size)
        columns.add(insertion, originalColumn)
        return state.copy(
            columns = columns,
            timelines = state.timelines + listOfNotNull(entry.timeline?.let { id to it }),
            notifications = state.notifications + listOfNotNull(entry.notifications?.let { id to it }),
            trends = state.trends + listOfNotNull(entry.trends?.let { id to it }),
            messages = state.messages + listOfNotNull(entry.messages?.let { id to it }),
            columnScrollPositions = state.columnScrollPositions + listOfNotNull(entry.scroll?.let { id to it }),
            removedColumns = state.removedColumns.filterNot { it.id == id },
        )
    }

    fun expire(state: DeckUiState, id: String): DeckUiState {
        removed.remove(id)
        return state.copy(removedColumns = state.removedColumns.filterNot { it.id == id })
    }

    fun clear() = removed.clear()

    private fun prune() { removed.entries.removeAll { it.value.deadline <= now() } }
}
