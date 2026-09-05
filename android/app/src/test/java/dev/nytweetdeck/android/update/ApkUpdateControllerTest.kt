package dev.nytweetdeck.android.update

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApkUpdateControllerTest {
    private fun apk(version: String) = LatestAndroidApk("android-v$version", "update.apk", "https://github.com/update.apk", 1)

    @Test
    fun comparesNumericVersionsAndPrereleases() {
        listOf("0.2.3", "0.2.2", "0.2.3-beta.2").forEach {
            assertFalse(apk(it).isNewerThan("0.2.3"))
        }
        listOf("0.2.4", "0.10.0", "1.0.0").forEach {
            assertTrue(apk(it).isNewerThan("0.2.3"))
        }
        assertTrue(apk("0.2.3").isNewerThan("0.2.3-beta.10"))
        assertTrue(apk("0.2.3-beta.10").isNewerThan("0.2.3-beta.2"))
        assertFalse(apk("0.2.3-beta.2").isNewerThan("0.2.3-beta.10"))
    }

    @Test
    fun currentAndOlderReleasesNeverDownload() = runTest {
        for (version in listOf("0.2.3", "0.2.2")) {
            var downloads = 0
            val controller = ApkUpdateController("0.2.3", { apk(version) }, { downloads++ })
            assertFalse(controller.status.value.canDownload)
            controller.check()
            assertEquals(ApkUpdateStatus.UP_TO_DATE, controller.status.value)
            controller.downloadLatest()
            controller.downloadLatest()
            assertEquals(0, downloads)
        }
    }

    @Test
    fun checkAndDownloadIgnoreRepeatedEventsAndReopeningAfterSuccess() = runTest {
        val response = CompletableDeferred<LatestAndroidApk>()
        val finished = CompletableDeferred<Unit>()
        var checks = 0
        var downloads = 0
        val controller = ApkUpdateController("0.2.3", { checks++; response.await() }, { downloads++; finished.await() })
        launch { controller.check() }
        runCurrent()
        assertEquals(ApkUpdateStatus.CHECKING, controller.status.value)
        controller.check()
        controller.downloadLatest()
        assertEquals(1, checks)
        response.complete(apk("0.2.4"))
        runCurrent()
        assertTrue(controller.status.value.canDownload)
        launch { controller.downloadLatest() }
        runCurrent()
        assertEquals(ApkUpdateStatus.DOWNLOAD_STARTED, controller.status.value)
        assertFalse(controller.status.value.canDownload)
        controller.downloadLatest()
        controller.check()
        assertEquals(1, downloads)
        finished.complete(Unit)
        runCurrent()
        controller.check()
        controller.downloadLatest()
        assertEquals(1, downloads)
        assertFalse(controller.status.value.canDownload)
    }

    @Test
    fun failedChecksAndDownloadsCanBeRetried() = runTest {
        var checks = 0
        var downloads = 0
        val controller = ApkUpdateController("0.2.3", {
            if (++checks == 1) throw IOException("offline")
            apk("0.2.4")
        }, {
            if (++downloads == 1) throw IOException("download failed")
        })
        controller.check()
        assertEquals(ApkUpdateStatus.FAILED, controller.status.value)
        assertTrue(controller.status.value.canDownload)
        controller.downloadLatest()
        assertEquals(ApkUpdateStatus.FAILED, controller.status.value)
        controller.downloadLatest()
        assertEquals(ApkUpdateStatus.DOWNLOAD_STARTED, controller.status.value)
        assertEquals(2, downloads)
    }
}
