package dev.nytweetdeck.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.xapi.XApiEnvironment
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XApiMetadataRefreshDeviceTest {
    @Test
    fun refreshesDefinitionsUsingTheDevicesBrowserCompatibleUserAgent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMetadata") == "true")

        val result = XApiEnvironment(instrumentation.targetContext).refreshMetadata()

        assertTrue("X Web API metadata refresh failed on the device.", result.succeeded)
    }
}
