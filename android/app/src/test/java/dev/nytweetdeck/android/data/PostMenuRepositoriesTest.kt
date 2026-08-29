package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.XApiProfile
import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PostMenuRepositoriesTest {
    private val restClient = AuthenticatedRestClient(
        OkHttpClient(),
        { XApiProfile("https://x.com/i/api/graphql", emptyList(), emptyMap(), emptyMap(), "https://api.x.com") },
        "test",
    )

    @Test
    fun mapsUserActionsToOfficialRestEndpoints() {
        val repository = UserActionRepository(restClient)
        assertEquals("followUser", repository.request("123", UserAction.FOLLOW).endpoint)
        assertEquals("muteUser", repository.request("123", UserAction.MUTE).endpoint)
        assertEquals("blockUser", repository.request("123", UserAction.BLOCK).endpoint)
        assertEquals(mapOf("user_id" to "123"), repository.request("123", UserAction.FOLLOW).parameters)
    }

    @Test
    fun mapsAndValidatesListMembershipMutations() {
        val repository = ListMembershipRepository(GraphQlExecutor { _, _, _, _ -> "{}" })
        assertEquals("listMemberAdd", repository.request("12", "34", ListMembershipAction.ADD).purpose)
        assertEquals("listMemberRemove", repository.request("12", "34", ListMembershipAction.REMOVE).purpose)
        assertThrows(IllegalArgumentException::class.java) {
            repository.request("bad", "34", ListMembershipAction.ADD)
        }
    }
}
