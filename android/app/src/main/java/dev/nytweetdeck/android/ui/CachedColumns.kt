package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.*
import dev.nytweetdeck.android.model.*

internal fun cachedColumns(
    layout: DeckUiState,
    account: AccountSecrets?,
    timelineRepository: TimelineRepository?,
    notificationRepository: NotificationRepository?,
    trendRepository: TrendRepository?,
): AccountColumnSnapshot {
    if (account == null) return AccountColumnSnapshot()
    val timelines = layout.columns.mapNotNull { column ->
        queryKind(column.kind)?.let { kind ->
            timelineRepository?.cached(account, kind, column.target)?.let { page ->
                column.id to ColumnTimelineState(TimelineLoadStatus.READY, page.posts, page.nextCursor)
            }
        }
    }.toMap()
    val notifications = layout.columns.filter { it.kind == ColumnKind.NOTIFICATIONS }.mapNotNull { column ->
        notificationRepository?.cached(account, layout.appLanguageTag)?.let {
            column.id to NotificationColumnState(status = TimelineLoadStatus.READY, page = it)
        }
    }.toMap()
    val trends = layout.columns.filter { it.kind == ColumnKind.TRENDS }.mapNotNull { column ->
        trendRepository?.cached(account, layout.appLanguageTag)?.let {
            column.id to TrendColumnState(status = TimelineLoadStatus.READY, page = it)
        }
    }.toMap()
    return AccountColumnSnapshot(timelines, notifications, trends)
}
