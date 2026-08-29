package dev.nytweetdeck.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XWebSessionCookiesTest {
    @Test
    fun requiresAuthCsrfAndNumericTwid() {
        val session = XWebSessionCookies.fromHeader(
            "auth_token=secret; ct0=csrf; twid=u%3D123456789",
        )

        requireNotNull(session)
        assertEquals("123456789", session.userId)
    }

    @Test
    fun rejectsMissingOrMalformedIdentity() {
        assertNull(XWebSessionCookies.fromHeader("auth_token=secret; ct0=csrf"))
        assertNull(XWebSessionCookies.fromHeader("auth_token=secret; ct0=csrf; twid=u%3Dabc"))
    }
}
