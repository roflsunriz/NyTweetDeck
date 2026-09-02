package dev.nytweetdeck.android.xapi

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live contract test: -Dnytd.liveMetadataTest=true. */
class XWebMetadataLiveTest {
    @Test
    fun resolvesEveryBundledOperationFromCurrentOfficialXAssets() {
        assumeTrue(System.getProperty("nytd.liveMetadataTest") == "true")
        val profile = XApiProfile.parse(resource("web-current.json"), resource("web-boolean-feature-defaults.json"))
        val resolved = XWebMetadataResolver(
            OkHttpClient(),
            normalizeMetadataUserAgent(
                "Mozilla/5.0 (Linux; Android 17; Pixel Build/TEST; wv) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                    "Chrome/151.0 Mobile Safari/537.36",
            ),
        ).resolve(profile)
        val updated = resolved.applyTo(profile)

        assertEquals(profile.operations.keys, updated.operations.keys)
    }

    private fun resource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "fixtureがありません: $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
