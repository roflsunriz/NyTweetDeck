package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.ColumnTimelineState
import dev.nytweetdeck.android.model.DirectMessageColumnState
import dev.nytweetdeck.android.model.NotificationColumnState
import dev.nytweetdeck.android.model.TrendColumnState

internal const val MAX_ADAPTIVE_DELAY_MILLIS = 5 * 60_000L
internal const val DEFAULT_VISIBILITY_REFRESH_DELAY_MILLIS = 750L

internal data class AccountColumnSnapshot(
    val timelines: Map<String, ColumnTimelineState> = emptyMap(),
    val notifications: Map<String, NotificationColumnState> = emptyMap(),
    val trends: Map<String, TrendColumnState> = emptyMap(),
    val messages: Map<String, DirectMessageColumnState> = emptyMap(),
)

internal fun queryKind(kind: ColumnKind): String? = when (kind) {
    ColumnKind.HOME_FOR_YOU -> "homeForYou"
    ColumnKind.HOME_FOLLOWING -> "homeFollowing"
    ColumnKind.HISTORY -> "history"
    ColumnKind.SEARCH -> "search"
    ColumnKind.USER -> "userPosts"
    ColumnKind.LIST -> "list"
    else -> null
}
