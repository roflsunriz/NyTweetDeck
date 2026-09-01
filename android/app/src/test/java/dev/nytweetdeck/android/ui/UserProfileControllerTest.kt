package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.UserDirectoryRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.UserProfileStatus
import dev.nytweetdeck.android.model.UserProfileTab
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileControllerTest {
    @Test
    fun opensDedicatedTabsAndRestoresThePreviousProfileOnBack() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val purposes = mutableListOf<String>()
            val repository = UserDirectoryRepository(
                GraphQlExecutor { _, purpose, variables, _ ->
                    purposes += purpose
                    when (purpose) {
                        "userByRestId" -> user(variables["userId"].toString())
                        "followersYouKnow" -> "{}"
                        "userPosts", "userReplies" -> timeline(purpose)
                        else -> error("unexpected purpose: $purpose")
                    }
                },
            )
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7"))
            val controller = UserProfileController(repository, this, dispatcher, { account() }, state)

            controller.open("42")
            advanceUntilIdle()
            assertEquals(UserProfileStatus.READY, state.value.userProfile.status)
            assertEquals(listOf("userPosts"), purposes.filter { it.startsWith("userP") })

            controller.selectTab(UserProfileTab.REPLIES)
            advanceUntilIdle()
            assertEquals(UserProfileTab.REPLIES, state.value.userProfile.tab)
            assertEquals("userReplies", purposes.last())

            controller.open("43")
            advanceUntilIdle()
            assertEquals("43", state.value.userProfile.userId)
            controller.close()
            assertEquals("42", state.value.userProfile.userId)
            assertEquals(UserProfileTab.REPLIES, state.value.userProfile.tab)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun account() = AccountSecrets(
        "7", "7", "user", "User", "bearer", "auth", "csrf", "profile",
    )

    private fun user(id: String) =
        """{"data":{"user":{"result":{"__typename":"User","rest_id":"$id",
        "core":{"screen_name":"user$id","name":"User $id"}}}}}"""

    private fun timeline(purpose: String) =
        """{"entries":[{"entryId":"tweet-$purpose","content":{"itemContent":
        {"tweet_results":{"result":{"__typename":"Tweet","rest_id":"${if (purpose == "userReplies") "102" else "101"}",
        "legacy":{"full_text":"$purpose"}}}}}}]}"""
}
