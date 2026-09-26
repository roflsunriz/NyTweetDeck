package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingWebSessionProviderTest {
    private val stored = XSessionCredentials("bearer", "stored-auth", "stored-csrf")
    private val pending = XSessionCredentials("bearer", "pending-auth", "pending-csrf")

    @Test
    fun prefersStoredSessionWithoutPendingVerification() {
        val provider = PendingWebSessionProvider { stored }

        assertEquals(stored, provider.current())
    }

    @Test
    fun exposesPendingSessionDuringVerificationAndRestoresAfterwards() {
        val provider = PendingWebSessionProvider { stored }

        val result = provider.runWith(pending) {
            assertEquals(pending, provider.current())
            "done"
        }

        assertEquals("done", result)
        assertEquals(stored, provider.current())
    }

    @Test
    fun fallsBackToPendingSessionWhenNothingIsStored() {
        val provider = PendingWebSessionProvider { null }

        assertNull(provider.current())
        provider.runWith(pending) {
            assertEquals(pending, provider.current())
        }
        assertNull(provider.current())
    }

    @Test
    fun restoresPreviousSessionWhenVerificationFails() {
        val provider = PendingWebSessionProvider { stored }

        assertThrows(IllegalStateException::class.java) {
            provider.runWith(pending) {
                assertEquals(pending, provider.current())
                error("verification failed")
            }
        }
        assertEquals(stored, provider.current())
    }
}
