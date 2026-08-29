package dev.nytweetdeck.android.xapi.live

import dev.nytweetdeck.android.data.AccountSecrets
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePipelineSubscriptionServiceTest {
    @Test
    fun mergesAccountTopicsDeduplicatesDmTopicsAndPublishesTypedCallbacks() {
        val client = FakeSessionClient()
        val service = LivePipelineSubscriptionService(client)
        val received = CopyOnWriteArrayList<LivePipelineEvent>()
        val listener = service.addListener(received::add)
        try {
            service.update(account("account-a", "42"), "column-a", listOf("1", "2"), false)
            val status = service.update(account("account-a", "42"), "column-b", listOf("2", "3"), true)

            val active = client.opens.last()
            assertEquals(
                setOf(
                    "/tweet_engagement/1",
                    "/tweet_engagement/2",
                    "/tweet_engagement/3",
                    "/dm_update/42",
                    "/dm_typing/42",
                ),
                active.topics,
            )
            assertTrue(client.opens.first().closed)
            assertTrue(status.connected)
            assertEquals(5, status.topicCount)

            active.emit(
                LivePipelineEvent.Engagement(
                    accountId = "account-a",
                    postId = "3",
                    counts = LiveEngagementCounts(null, null, null, 9, null, null),
                    occurredAt = Instant.parse("2026-08-29T00:00:00Z"),
                ),
            )
            active.emit(
                LivePipelineEvent.DirectMessageTyping(
                    accountId = "account-a",
                    userId = "42",
                    payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"typing":true}"""),
                    occurredAt = Instant.parse("2026-08-29T00:00:01Z"),
                ),
            )
            assertEquals(2, received.size)
            assertTrue(received[0] is LivePipelineEvent.Engagement)
            assertTrue(received[1] is LivePipelineEvent.DirectMessageTyping)
        } finally {
            listener.close()
            service.closeAll()
        }
    }

    @Test
    fun keepsAccountsSeparatedAndClosesOnlyTheForegroundAccount() {
        val client = FakeSessionClient()
        val service = LivePipelineSubscriptionService(client)

        service.update(account("account-a", "42"), "column-a", listOf("1"), false)
        service.update(account("account-b", "99"), "column-b", listOf("2"), true)
        val accountA = client.opens[0]
        val accountB = client.opens[1]

        service.closeAccount("account-a")

        assertTrue(accountA.closed)
        assertFalse(accountB.closed)
        assertEquals("account-b", accountB.accountId)
        service.closeAll()
        assertTrue(accountB.closed)
    }

    @Test
    fun rejectsMoreThanOneHundredMergedDisplayPostsWithoutOpeningConnection() {
        val client = FakeSessionClient()
        val service = LivePipelineSubscriptionService(client)
        val firstPosts = (1..100).map(Int::toString)
        service.update(account("account-a", "42"), "first", firstPosts, false)

        assertThrows(IllegalArgumentException::class.java) {
            service.update(account("account-a", "42"), "second", listOf("101"), false)
        }

        assertEquals(1, client.opens.size)
        assertEquals(100, client.opens.single().topics.size)
        service.closeAll()
    }

    private fun account(accountId: String, userId: String) = AccountSecrets(
        accountId, userId, "user$userId", "User $userId", "bearer-$accountId", "auth-$accountId", "csrf-$accountId", "profile-$accountId",
    )

    private class FakeSessionClient : LivePipelineSessionClient {
        val opens = CopyOnWriteArrayList<FakeConnection>()

        override fun open(
            account: AccountSecrets,
            topics: Set<String>,
            eventConsumer: (LivePipelineEvent) -> Unit,
        ): LivePipelineConnection {
            return FakeConnection(account.accountId, topics, eventConsumer).also(opens::add)
        }
    }

    private class FakeConnection(
        val accountId: String,
        val topics: Set<String>,
        private val consumer: (LivePipelineEvent) -> Unit,
    ) : LivePipelineConnection {
        var closed = false

        fun emit(event: LivePipelineEvent) {
            consumer(event)
        }

        override fun close() {
            closed = true
        }
    }
}
