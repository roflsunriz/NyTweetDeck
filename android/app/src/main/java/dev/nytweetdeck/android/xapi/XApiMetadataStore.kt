package dev.nytweetdeck.android.xapi

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

data class XApiMetadataRefreshResult(
    val succeeded: Boolean,
    val sourceVersion: String? = null,
)

fun interface XApiMetadataRefresher {
    fun refreshMetadata(): XApiMetadataRefreshResult
}

/** Atomically exposes only complete metadata snapshots and retains the last valid profile. */
class XApiMetadataStore(
    private val bundledProfile: XApiProfile,
    private val resolver: () -> XWebMetadataResolver.ResolvedMetadata,
) : XApiMetadataRefresher {
    private val activeProfile = AtomicReference(bundledProfile)

    fun currentProfile(): XApiProfile = activeProfile.get()

    @Synchronized
    override fun refreshMetadata(): XApiMetadataRefreshResult = runCatching {
        val resolved = resolver()
        val updated = resolved.applyTo(bundledProfile)
        activeProfile.set(updated)
        XApiMetadataRefreshResult(true, resolved.sourceVersion)
    }.getOrElse { error ->
        Log.w("XApiMetadataStore", "X API定義の更新に失敗しました。", error)
        XApiMetadataRefreshResult(false)
    }
}
