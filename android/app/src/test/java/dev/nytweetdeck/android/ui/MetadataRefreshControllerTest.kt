package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.xapi.XApiMetadataRefreshResult
import dev.nytweetdeck.android.xapi.XApiMetadataRefresher
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataRefreshControllerTest {
    @Test
    fun refreshesOnDemandHonorsTtlAndPreservesTheLastSuccessOnFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var now = 1_000L
            var calls = 0
            var succeeds = true
            val state = MutableStateFlow(DeckUiState())
            val refresher = XApiMetadataRefresher {
                calls++
                XApiMetadataRefreshResult(succeeds, "main-$calls.js")
            }
            val controller = MetadataRefreshController(
                refresher, this, dispatcher, state, { now }, refreshIntervalMillis = 100L,
            )

            controller.refresh(force = true)
            advanceUntilIdle()
            assertEquals(1, calls)
            assertFalse(state.value.xApiMetadataRefreshing)
            assertFalse(state.value.xApiMetadataError)
            assertNotNull(state.value.xApiMetadataLastSuccessAt)
            assertEquals("main-1.js", state.value.xApiMetadataSourceVersion)

            controller.refresh()
            advanceUntilIdle()
            assertEquals(1, calls)

            now += 100L
            succeeds = false
            controller.refresh()
            advanceUntilIdle()
            assertEquals(2, calls)
            assertTrue(state.value.xApiMetadataError)
            assertEquals("main-1.js", state.value.xApiMetadataSourceVersion)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
