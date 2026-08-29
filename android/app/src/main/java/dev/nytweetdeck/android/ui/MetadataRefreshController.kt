package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.xapi.XApiMetadataRefresher
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MetadataRefreshController(
    private val refresher: XApiMetadataRefresher?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<DeckUiState>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMillis: Long = DEFAULT_METADATA_REFRESH_INTERVAL_MILLIS,
) {
    private var job: Job? = null
    private var lastAttemptAt: Long? = null

    init {
        require(refreshIntervalMillis > 0L) { "metadata更新間隔が不正です。" }
    }

    fun refresh(force: Boolean = false) {
        val service = refresher ?: return
        if (job?.isActive == true) return
        val now = nowMillis()
        val previousAttempt = lastAttemptAt
        if (!force && previousAttempt != null && now - previousAttempt < refreshIntervalMillis) return
        lastAttemptAt = now
        state.update { it.copy(xApiMetadataRefreshing = true, xApiMetadataError = false) }
        job = scope.launch(ioDispatcher) {
            val result = runCatching(service::refreshMetadata).getOrNull()
            val completedAt = nowMillis()
            withContext(Dispatchers.Main.immediate) {
                state.update { current ->
                    current.copy(
                        xApiMetadataRefreshing = false,
                        xApiMetadataError = result?.succeeded != true,
                        xApiMetadataLastSuccessAt = if (result?.succeeded == true) {
                            Instant.ofEpochMilli(completedAt).toString()
                        } else {
                            current.xApiMetadataLastSuccessAt
                        },
                        xApiMetadataSourceVersion = if (result?.succeeded == true) {
                            result.sourceVersion ?: current.xApiMetadataSourceVersion
                        } else {
                            current.xApiMetadataSourceVersion
                        },
                    )
                }
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
    }

    companion object {
        internal const val DEFAULT_METADATA_REFRESH_INTERVAL_MILLIS = 6 * 60 * 60_000L
    }
}
