package dev.nytweetdeck.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalUrlPolicyTest {
    @Test
    fun allowsCredentialFreeHttpsAndOptionalVerifiedHostFamilies() {
        assertEquals(
            "https://help.x.com/article?id=1#source",
            verifiedExternalHttpsUrl("https://help.x.com/article?id=1#source"),
        )
        assertEquals(
            "https://help.x.com/article",
            verifiedExternalHttpsUrl("https://help.x.com/article", setOf("x.com")),
        )
        assertNull(verifiedExternalHttpsUrl("https://example.com/article", setOf("x.com")))
    }

    @Test
    fun rejectsCredentialsUnsafeSchemesAndUnexpectedPorts() {
        assertNull(verifiedExternalHttpsUrl("https://token@x.com/article"))
        assertNull(verifiedExternalHttpsUrl("javascript:alert(1)"))
        assertNull(verifiedExternalHttpsUrl("intent://x.com/#Intent;scheme=https;end"))
        assertNull(verifiedExternalHttpsUrl("http://x.com/article"))
        assertNull(verifiedExternalHttpsUrl("https://x.com:8443/article"))
    }
}
