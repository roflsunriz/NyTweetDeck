package dev.nytweetdeck.android.xapi

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XAccountVerifierTest {
    @Test
    fun findsOnlyTheExpectedUserInNestedResponse() {
        val root = Json.parseToJsonElement(
            """{
              "data": {
                "decoy": {"__typename":"User","rest_id":"1","core":{"screen_name":"decoy"}},
                "target": {"result":{"__typename":"User","rest_id":"42","core":{"screen_name":"nytd"}}}
              }
            }""".trimIndent(),
        )

        val user = XAccountVerifier.findUser(root, "42")

        assertEquals("42", user?.get("rest_id")?.toString()?.trim('"'))
        assertNull(XAccountVerifier.findUser(root, "99"))
    }
}
