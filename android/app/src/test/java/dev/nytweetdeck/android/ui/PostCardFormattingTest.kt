package dev.nytweetdeck.android.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostCardFormattingTest {
    @Test
    fun parsesIsoAndXWebCreatedAtFormats() {
        val expected = Instant.parse("2026-08-29T00:00:00Z")

        assertEquals(expected, parsePostInstant("2026-08-29T00:00:00Z"))
        assertEquals(expected, parsePostInstant("Sat Aug 29 00:00:00 +0000 2026"))
    }

    @Test
    fun rejectsMissingOrMalformedTime() {
        assertNull(parsePostInstant(null))
        assertNull(parsePostInstant("not-a-time"))
    }
}
