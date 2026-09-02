package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XApiEnvironmentTest {
    @Test
    fun metadataResolverUsesABrowserCompatibleUserAgentInsteadOfWebViewMode() {
        val webViewUserAgent =
            "Mozilla/5.0 (Linux; Android 17; Pixel 10a Build/TEST; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/151.0.0.0 Mobile Safari/537.36"

        val normalized = normalizeMetadataUserAgent(webViewUserAgent)

        assertFalse(normalized.contains("; wv"))
        assertFalse(normalized.contains("Version/4.0"))
        assertTrue(normalized.contains("Android 17"))
        assertTrue(normalized.contains("Chrome/151.0.0.0"))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 17; Pixel 10a Build/TEST) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Mobile Safari/537.36",
            normalized,
        )
    }
}
