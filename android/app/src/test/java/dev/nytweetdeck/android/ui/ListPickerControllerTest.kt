package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.ListDirectoryRepository
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.ListPickerScope
import dev.nytweetdeck.android.model.TimelineLoadStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListPickerControllerTest {
    @Test
    fun switchingAccountsCancelsAnInFlightPrefetchAndStartsTheNewAccountImmediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val executor = GraphQlExecutor { credentials, purpose, _, _ ->
                listResponse(credentials.bearerToken.removePrefix("bearer-"), purpose)
            }
            val accounts = mapOf("a" to account("a"), "b" to account("b"))
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "a"))
            val controller = ListPickerController(
                ListDirectoryRepository(executor), this, dispatcher, accounts::get, state,
            )

            controller.accountChanged("a")
            state.value = state.value.copy(selectedAccountId = "b")
            controller.accountChanged("b")
            advanceUntilIdle()

            assertEquals(listOf("b-combinedLists"), state.value.listPicker.mineOptions.map { it.id })
            assertFalse(state.value.listPicker.isRefreshing)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun prefetchesPerAccountRestoresImmediatelyAndKeepsCandidatesOnRefreshFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var failAccountA = false
            val executor = GraphQlExecutor { credentials, purpose, _, _ ->
                if (credentials.bearerToken == "bearer-a" && failAccountA) error("offline")
                listResponse(credentials.bearerToken.removePrefix("bearer-"), purpose)
            }
            val accounts = mapOf("a" to account("a"), "b" to account("b"))
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "a"))
            val controller = ListPickerController(
                ListDirectoryRepository(executor),
                this,
                dispatcher,
                accounts::get,
                state,
            )

            controller.accountChanged("a")
            assertEquals(TimelineLoadStatus.LOADING, state.value.listPicker.status)
            advanceUntilIdle()
            assertEquals(listOf("a-combinedLists"), state.value.listPicker.mineOptions.map { it.id })
            assertEquals(listOf("a-listsDiscovery"), state.value.listPicker.suggestedOptions.map { it.id })

            failAccountA = true
            controller.open()
            assertEquals(listOf("a-combinedLists"), state.value.listPicker.mineOptions.map { it.id })
            assertTrue(state.value.listPicker.isRefreshing)
            advanceUntilIdle()
            assertEquals(TimelineLoadStatus.READY, state.value.listPicker.status)
            assertEquals(listOf("a-combinedLists"), state.value.listPicker.mineOptions.map { it.id })
            assertTrue(state.value.listPicker.refreshFailed)

            state.value = state.value.copy(selectedAccountId = "b")
            controller.accountChanged("b")
            assertTrue(state.value.listPicker.mineOptions.isEmpty())
            advanceUntilIdle()
            assertEquals(listOf("b-combinedLists"), state.value.listPicker.mineOptions.map { it.id })

            state.value = state.value.copy(selectedAccountId = "a")
            controller.accountChanged("a")
            assertEquals(listOf("a-combinedLists"), state.value.listPicker.mineOptions.map { it.id })
            assertTrue(state.value.listPicker.isRefreshing)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun searchKeepsItsLastSuccessfulCandidatesWhenRevalidationFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var failSearch = false
            val executor = GraphQlExecutor { _, purpose, variables, _ ->
                if (purpose == "listSearch" && failSearch) error("offline")
                listResponse("a", purpose + (variables["rawQuery"] ?: ""))
            }
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "a"))
            val accounts = mapOf("a" to account("a"), "b" to account("b"))
            val controller = ListPickerController(
                ListDirectoryRepository(executor), this, dispatcher, accounts::get, state,
            )
            controller.accountChanged("a")
            advanceUntilIdle()

            controller.selectScope(ListPickerScope.SEARCH)
            controller.search("compose")
            advanceUntilIdle()
            val successful = state.value.listPicker.searchOptions
            assertFalse(successful.isEmpty())

            state.value = state.value.copy(selectedAccountId = "b")
            controller.accountChanged("b")
            advanceUntilIdle()
            state.value = state.value.copy(selectedAccountId = "a")
            controller.accountChanged("a")
            assertEquals("compose", state.value.listPicker.searchQuery)
            assertEquals(successful, state.value.listPicker.searchOptions)
            advanceUntilIdle()

            failSearch = true
            controller.search("compose")
            assertEquals(successful, state.value.listPicker.searchOptions)
            advanceUntilIdle()
            assertEquals(successful, state.value.listPicker.searchOptions)
            assertTrue(state.value.listPicker.refreshFailed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun account(id: String) = AccountSecrets(
        id, id, id, id.uppercase(), "bearer-$id", "auth", "csrf", "profile-$id",
    )

    private fun listResponse(account: String, purpose: String): String {
        val id = "$account-$purpose"
        return """{"items":[{"__typename":"TimelineTwitterList","list":{
            "id_str":"$id","name":"$id","member_count":1,"subscriber_count":2}}]}"""
            .trimIndent()
    }
}
