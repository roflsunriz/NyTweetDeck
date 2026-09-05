package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.CommunityNoteRepository
import dev.nytweetdeck.android.model.CommunityNote
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.TranslationCandidate
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityNoteTranslationControllerTest {
    @Test fun unavailableRetriesAndTargetChangeDiscardsOldResponse() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var available = false
            var changeTarget = false
            val state = MutableStateFlow(DeckUiState(selectedAccountId = "7", translationLanguageTag = "ja"))
            val calls = mutableListOf<String>()
            val repository = CommunityNoteRepository(GraphQlExecutor { _, purpose, _, language ->
                assertEquals("communityNote", purpose)
                calls += language
                if (changeTarget) state.value = state.value.copy(translationLanguageTag = "fr", postTranslations = emptyMap())
                """{"data":{"birdwatch_note_by_rest_id":{"rest_id":"55","language":"en",
                "data_v1":{"summary":{"text":"Original"}},"tweet_results":{"result":{"rest_id":"123"}},
                "grok_translated_community_note_with_availability":{"is_available":$available,"data":{
                "translation_available":$available,"destination_language":"$language","source_language":"en","translation":"Translated"}}}}}"""
            })
            val account = AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile")
            val controller = CommunityNoteController(repository, this, dispatcher, { account }, state)
            val note = CommunityNote(null, "Original", null, "55", "en", false)
            val candidate = TranslationCandidate("note:55", "en", null, "Original", note)
            controller.translate(candidate)
            advanceUntilIdle()
            assertTrue(state.value.postTranslations["note:55"]!!.unavailable)
            controller.translate(candidate)
            advanceUntilIdle()
            assertEquals(1, calls.size)
            available = true
            controller.translate(candidate, manual = true)
            advanceUntilIdle()
            assertEquals(TranslationLoadStatus.READY, state.value.postTranslations["note:55"]!!.status)
            state.value = state.value.copy(postTranslations = emptyMap())
            controller.translate(candidate)
            advanceUntilIdle()
            assertEquals(TranslationLoadStatus.READY, state.value.postTranslations["note:55"]!!.status)
            assertEquals(listOf("ja", "ja"), calls)
            state.value = state.value.copy(translationLanguageTag = "es", postTranslations = emptyMap())
            changeTarget = true
            controller.translate(candidate)
            advanceUntilIdle()
            assertTrue(state.value.postTranslations.isEmpty())
            assertEquals(listOf("ja", "ja", "es"), calls)
        } finally { Dispatchers.resetMain() }
    }
}
