package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XApiProfileTest {
    @Test
    fun desktopWebProfileIsAcceptedByAndroidClient() {
        val profileJson = resource("web-current.json")
        val defaultsJson = resource("web-boolean-feature-defaults.json")

        val profile = XApiProfile.parse(profileJson, defaultsJson)

        assertEquals("https://x.com/i/api/graphql", profile.graphqlBaseUrl)
        assertTrue(profile.operations.keys.containsAll(
            setOf("homeForYou", "homeFollowing", "userByRestId", "createPost", "bookmark"),
        ))
        assertTrue(profile.featuresFor(profile.requireOperation("homeForYou")).isNotEmpty())
        assertEquals("UserRepliesTimeline", profile.requireOperation("userReplies").operationName)
        assertEquals("dRUXRSlEIPlVmPgOQ8Z43g", profile.requireOperation("userReplies").operationId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOperationTypeIsRejected() {
        XApiProfile.parse(
            """{
                "graphqlBaseUri":"https://x.com/i/api/graphql",
                "featureKeys":[],
                "graphqlOperations":{"home":{"operationId":"id","operationName":"Home","type":"OTHER"}}
            }""".trimIndent(),
            """{"defaults":{}}""",
        )
    }

    private fun resource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource(name),
    ).readText()
}
