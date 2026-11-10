package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.VideoQuality
import java.util.Locale

internal data class VideoPlaybackSnapshot(
    val position: Long = 0L,
    val playWhenReady: Boolean,
    val muted: Boolean = true,
    val volume: Float,
    val quality: VideoQuality,
    val loop: Boolean,
)

/** View instances share media state, while the coordinator uses distinct instance ownership. */
internal object VideoPlaybackStates {
    // Bounded in-memory navigation history; credentials and URLs are never persisted.
    private const val MAX_ENTRIES = 64
    private val states = LinkedHashMap<String, VideoPlaybackSnapshot>()

    fun get(id: String): VideoPlaybackSnapshot? = states[id]

    fun save(id: String, value: VideoPlaybackSnapshot) {
        states.remove(id)
        states[id] = value
        while (states.size > MAX_ENTRIES) states.remove(states.keys.first())
    }

    internal fun clear() = states.clear()
}

internal fun formatVideoTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%02d:%02d:%02d", hours, seconds / 60L % 60L, seconds % 60L)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
}
