package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.ColumnTimelineState
import dev.nytweetdeck.android.model.DirectMessageColumnState
import dev.nytweetdeck.android.model.NotificationColumnState
import dev.nytweetdeck.android.model.TrendColumnState
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.DisplaySettings
import dev.nytweetdeck.android.model.DeckColumn
import java.util.Locale
import java.util.UUID

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

internal fun DeckUiState.displaySettings(): DisplaySettings = DisplaySettings(
    themeMode,
    fontSize,
    accentColor,
    compactDensity,
    reduceMotion,
    mediaPreview,
    videoAutoplay,
    videoLoop,
    videoVolume,
)

internal fun DeckUiState.withDisplaySettings(settings: DisplaySettings): DeckUiState = copy(
    themeMode = settings.themeMode,
    useDarkTheme = settings.themeMode != dev.nytweetdeck.android.model.ThemeMode.LIGHT,
    fontSize = settings.fontSize,
    accentColor = settings.accentColor,
    compactDensity = settings.compactDensity,
    reduceMotion = settings.reduceMotion,
    mediaPreview = settings.mediaPreview,
    videoAutoplay = settings.videoAutoplay,
    videoLoop = settings.videoLoop,
    videoVolume = settings.videoVolume,
)

internal fun DeckUiState.withTrendSearch(query: String, addColumn: Boolean): DeckUiState {
    val normalized = query.trim()
    require(normalized.isNotEmpty() && normalized.length <= 100) { "検索語句が不正です。" }
    val history = listOf(normalized) + trendSearchHistory.filterNot {
        it.lowercase(Locale.ROOT) == normalized.lowercase(Locale.ROOT)
    }
    return copy(
        columns = if (addColumn) {
            columns + DeckColumn(UUID.randomUUID().toString(), ColumnKind.SEARCH, normalized, normalized)
        } else {
            columns
        },
        selectedMenu = if (addColumn) ColumnKind.SEARCH else selectedMenu,
        trendSearchHistory = history.take(20),
    )
}
