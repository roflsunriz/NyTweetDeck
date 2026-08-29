package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UserDirectoryRepositoryTest {
    @Test
    fun stripsAtSignAndResolvesCurrentUserSchema() {
        var screenName: Any? = null
        val repository = UserDirectoryRepository(
            GraphQlExecutor { _, _, variables, _ ->
                screenName = variables["screen_name"]
                """{"data":{"user":{"result":{"__typename":"User","rest_id":"42",
                "core":{"screen_name":"alice","name":"Alice"}}}}}"""
            },
        )

        val result = repository.resolve(account(), " @alice ")

        assertEquals("alice", screenName)
        assertEquals("42", result.id)
        assertThrows(IllegalArgumentException::class.java) { repository.resolve(account(), "not valid!") }
    }

    private fun account() = AccountSecrets(
        "7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7",
    )
}
