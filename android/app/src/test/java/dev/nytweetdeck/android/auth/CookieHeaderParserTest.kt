package dev.nytweetdeck.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CookieHeaderParserTest {
    @Test
    fun parsesRequiredCookiesWithoutLosingEqualsCharacters() {
        val values = CookieHeaderParser.parse(
            "guest_id=guest; auth_token=auth==; ct0=csrf-value; malformed",
        )

        assertEquals("auth==", values["auth_token"])
        assertEquals("csrf-value", values["ct0"])
        assertFalse(values.containsKey("malformed"))
    }

    @Test
    fun laterCookieValueReplacesEarlierDuplicate() {
        val values = CookieHeaderParser.parse("ct0=old; ct0=new")

        assertEquals("new", values["ct0"])
    }
}
