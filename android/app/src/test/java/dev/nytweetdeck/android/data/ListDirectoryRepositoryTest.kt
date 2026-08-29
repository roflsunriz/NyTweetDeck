package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ListDirectoryRepositoryTest {
    @Test
    fun mineUsesActiveUserAndSearchRequiresQuery() {
        var purpose: String? = null
        var userId: Any? = null
        val repository = ListDirectoryRepository(
            GraphQlExecutor { _, observedPurpose, variables, _ ->
                purpose = observedPurpose
                userId = variables["userId"]
                "{\"data\":{}}"
            },
        )

        repository.load(account(), "mine")
        assertEquals("combinedLists", purpose)
        assertEquals("7", userId)
        assertThrows(IllegalArgumentException::class.java) {
            repository.load(account(), "search", " ")
        }
    }

    private fun account() = AccountSecrets(
        "7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile-7",
    )
}
