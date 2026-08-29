package dev.nytweetdeck.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostShareTest {
    @Test
    fun createsOnlyCanonicalNumericPostLinks() {
        assertEquals("https://x.com/i/status/123", postShareUrl("123"))
        assertNull(postShareUrl("../123"))
        assertNull(postShareUrl("1?auth_token=secret"))
        assertNull(postShareUrl(""))
    }
}
