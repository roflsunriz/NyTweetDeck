package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.ColumnTimelineState
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.TimelineLoadStatus
import dev.nytweetdeck.android.xapi.live.LiveEngagementCounts
import dev.nytweetdeck.android.xapi.live.LivePipelineConnection
import dev.nytweetdeck.android.xapi.live.LivePipelineEvent
import dev.nytweetdeck.android.xapi.live.LivePipelineSessionClient
import dev.nytweetdeck.android.xapi.live.LivePipelineSubscriptionService
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDeckControllerTest {
    @Test
    fun engagementAndDmEventsUpdateOnlyTheSelectedAccount() = runTest {
        val client = FakeLiveClient()
        val service = LivePipelineSubscriptionService(client)
        val account = AccountSecrets("7", "7", "user", "User", "bearer", "auth", "csrf", "profile")
        val state = MutableStateFlow(
            DeckUiState(
                columns = listOf(
                    DeckColumn("home", ColumnKind.HOME_FOR_YOU, "Home"),
                    DeckColumn("dm", ColumnKind.MESSAGES, "DM"),
                ),
                selectedAccountId = "7",
                timelines = mapOf(
                    "home" to ColumnTimelineState(
                        TimelineLoadStatus.READY,
                        listOf(post()),
                    ),
                ),
            ),
        )
        val dmRefreshes = mutableListOf<String>()
        val controller = LiveDeckController(
            service,
            this,
            { account },
            state,
            dmRefreshes::add,
        )

        controller.setVisibleColumns(setOf("home", "dm"))
        controller.setForeground(true)
        advanceUntilIdle()
        assertTrue(client.topics.any { it == "/tweet_engagement/123" })
        assertTrue(client.topics.any { it == "/dm_update/7" })

        client.emit(
            LivePipelineEvent.Engagement(
                "7",
                "123",
                LiveEngagementCounts(2, 3, 4, 5, 6, 7),
                Instant.parse("2026-08-29T00:00:00Z"),
            ),
        )
        client.emit(
            LivePipelineEvent.DirectMessageUpdate(
                "7", "7", JsonNull, Instant.parse("2026-08-29T00:00:01Z"),
            ),
        )

        val updated = state.value.timelines["home"]?.posts?.single()
        assertEquals(5L, updated?.likeCount)
        assertEquals(7L, updated?.viewCount)
        assertEquals(listOf("dm"), dmRefreshes)
        controller.close()
    }

    private fun post() = Post(
        "123", "post", "en", null, Author("7", "user", "User", null, false), null, null,
        0, 0, 0, 0, 0, 0, false, false, false,
        null, null, null, null, null, null, null, emptyList(),
    )

    private class FakeLiveClient : LivePipelineSessionClient {
        var topics: Set<String> = emptySet()
        private var consumer: ((LivePipelineEvent) -> Unit)? = null

        override fun open(
            account: AccountSecrets,
            topics: Set<String>,
            eventConsumer: (LivePipelineEvent) -> Unit,
        ): LivePipelineConnection {
            this.topics = topics
            consumer = eventConsumer
            return object : LivePipelineConnection {
                override fun close() = Unit
            }
        }

        fun emit(event: LivePipelineEvent) = requireNotNull(consumer).invoke(event)
    }
}
