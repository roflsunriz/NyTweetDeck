package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineRepositoryTest {
    @Test
    fun loadsAndNormalizesAuthenticatedHomeTimeline() {
        var observedPurpose: String? = null
        var observedCount: Any? = null
        val repository = TimelineRepository(
            GraphQlExecutor { _, purpose, variables, _ ->
                observedPurpose = purpose
                observedCount = variables["count"]
                """{
                  "data":{"home":{"tweet":{
                    "__typename":"Tweet","rest_id":"42",
                    "legacy":{"full_text":"Android timeline","created_at":"Sat Aug 29 00:00:00 +0000 2026"},
                    "core":{"user_results":{"result":{"__typename":"User","rest_id":"7","core":{"screen_name":"nytd","name":"NyTD"}}}}
                  }}}
                }""".trimIndent()
            },
        )

        val page = repository.load(account(), "homeForYou")

        assertEquals("homeForYou", observedPurpose)
        assertEquals(20, observedCount)
        assertEquals("Android timeline", page.posts.single().text)
    }

    private fun account() = AccountSecrets(
        accountId = "7",
        userId = "7",
        username = "nytd",
        displayName = "NyTD",
        webBearerToken = "bearer",
        authToken = "auth",
        csrfToken = "csrf",
        profileName = "profile-7",
    )
}
