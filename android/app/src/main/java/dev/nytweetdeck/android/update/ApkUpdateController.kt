package dev.nytweetdeck.android.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ApkUpdateStatus {
    NONE, CHECKING, AVAILABLE, UP_TO_DATE, DOWNLOAD_STARTED, FAILED;

    val canDownload: Boolean get() = this == AVAILABLE || this == FAILED
}

/** Owns update eligibility so repeated UI events cannot enqueue the same download. */
internal class ApkUpdateController(
    private val currentVersion: String,
    private val latestApk: suspend () -> LatestAndroidApk,
    private val download: suspend (LatestAndroidApk) -> Unit,
) {
    private val mutableStatus = MutableStateFlow(ApkUpdateStatus.NONE)
    val status = mutableStatus.asStateFlow()
    private var availableApk: LatestAndroidApk? = null

    suspend fun check() {
        if (status.value == ApkUpdateStatus.CHECKING || status.value == ApkUpdateStatus.DOWNLOAD_STARTED) return
        mutableStatus.value = ApkUpdateStatus.CHECKING
        try {
            availableApk = latestApk().takeIf { it.isNewerThan(currentVersion) }
            mutableStatus.value = if (availableApk == null) ApkUpdateStatus.UP_TO_DATE else ApkUpdateStatus.AVAILABLE
        } catch (cancelled: CancellationException) {
            mutableStatus.value = ApkUpdateStatus.NONE
            throw cancelled
        } catch (_: Exception) {
            mutableStatus.value = ApkUpdateStatus.FAILED
        }
    }

    suspend fun downloadLatest() {
        if (!status.value.canDownload) return
        // Revalidate on retry, including checks which previously failed.
        if (status.value == ApkUpdateStatus.FAILED) check()
        if (status.value != ApkUpdateStatus.AVAILABLE) return
        val apk = requireNotNull(availableApk)
        mutableStatus.value = ApkUpdateStatus.DOWNLOAD_STARTED
        try {
            download(apk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableStatus.value = ApkUpdateStatus.FAILED
        }
    }
}

internal fun LatestAndroidApk.isNewerThan(currentVersion: String): Boolean {
    fun parse(value: String): Pair<List<java.math.BigInteger>, String?> {
        val match = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[.-]([0-9A-Za-z.-]+))?")
            .matchEntire(value) ?: throw IllegalArgumentException("Invalid Android version")
        return (1..3).map { match.groupValues[it].toBigInteger() } to
            match.groupValues[4].takeIf { it.isNotEmpty() }
    }
    val (latest, latestPre) = parse(tagName.removePrefix("android-v"))
    val (current, currentPre) = parse(currentVersion)
    latest.zip(current).forEach { (left, right) ->
        if (left != right) return left > right
    }
    if (latestPre == currentPre) return false
    if (latestPre == null) return true
    if (currentPre == null) return false
    val left = latestPre.split('.')
    val right = currentPre.split('.')
    left.zip(right).forEach { (a, b) ->
        if (a != b) {
            val an = a.toBigIntegerOrNull()
            val bn = b.toBigIntegerOrNull()
            return when {
                an != null && bn != null -> an > bn
                an != null -> false
                bn != null -> true
                else -> a > b
            }
        }
    }
    return left.size > right.size
}
