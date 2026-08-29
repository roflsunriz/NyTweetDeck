package dev.nytweetdeck.android.security

import dev.nytweetdeck.android.auth.XWebSessionCookies
import dev.nytweetdeck.android.model.CapturedWebSession
import dev.nytweetdeck.android.xapi.XSessionCredentials
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {
    @Test
    fun authenticationDataClassesNeverRenderTokenValues() {
        val secret = "must-never-appear"
        val values = listOf(
            XSessionCredentials(secret, secret, secret).toString(),
            CapturedWebSession("profile", "123", secret, secret).toString(),
            XWebSessionCookies(secret, secret, "123").toString(),
        )

        assertTrue(values.all { it.contains("<redacted>") })
        assertFalse(values.any { it.contains(secret) })
    }
}
