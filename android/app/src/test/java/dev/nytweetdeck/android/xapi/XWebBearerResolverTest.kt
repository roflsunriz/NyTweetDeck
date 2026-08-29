package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XWebBearerResolverTest {
    @Test
    fun extractsCurrentWebAssetChain() {
        val entry = XWebBearerResolver.extractEntryAsset(
            "<script src=\"https://abs.twimg.com/x-web/x-web/entry-client-logged-out-Ab_12.js\"></script>",
        )
        val guest = XWebBearerResolver.extractGuestAsset(
            "load('assets/guest-token-token_99.js')",
        )
        val bearer = XWebBearerResolver.extractBearer("header: `Bearer AAAAA%2Ftoken-value`")

        assertEquals("entry-client-logged-out-Ab_12.js", entry)
        assertEquals("assets/guest-token-token_99.js", guest)
        assertEquals("AAAAA%2Ftoken-value", bearer)
    }

    @Test
    fun rejectsDocumentsWithoutExpectedOfficialAssets() {
        assertNull(XWebBearerResolver.extractEntryAsset("https://example.com/entry.js"))
        assertNull(XWebBearerResolver.extractGuestAsset("assets/main.js"))
        assertNull(XWebBearerResolver.extractBearer("Bearer not-an-x-web-token"))
    }
}
