package dev.nytweetdeck.android.xapi.live

import dev.nytweetdeck.android.data.AccountSecrets
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import dev.nytweetdeck.android.xapi.XApiProfile
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePipelineClientTest {
    @Test
    fun createsSafeOfficialEventRequestWithTheFirstTwentySortedTopics() {
        val client = LivePipelineClient(
            connector = FakeConnector(),
            profileProvider = ::profile,
            userAgent = "NyTD-Test-UA",
        )
        val topics = (1..20).map { id -> "/tweet_engagement/$id" }.toSet()

        val request = client.createEventRequest(account("account-1", "1"), topics)

        assertEquals("api.x.com", request.url.host)
        assertEquals("text/event-stream", request.header("Accept"))
        assertEquals("Bearer bearer-account-1", request.header("Authorization"))
        assertEquals(
            "/tweet_engagement/1,/tweet_engagement/10,/tweet_engagement/11,/tweet_engagement/12," +
                "/tweet_engagement/13,/tweet_engagement/14,/tweet_engagement/15,/tweet_engagement/16," +
                "/tweet_engagement/17,/tweet_engagement/18,/tweet_engagement/19,/tweet_engagement/2," +
                "/tweet_engagement/20,/tweet_engagement/3,/tweet_engagement/4,/tweet_engagement/5," +
                "/tweet_engagement/6,/tweet_engagement/7,/tweet_engagement/8,/tweet_engagement/9",
            request.url.queryParameter("topic"),
        )
    }

    @Test
    fun subscribesRemainingTopicsAfterConfigAndCancelsForegroundConnection() {
        val stream = BlockingStream(
            """{"topic":"/system/config","payload":{"config":{"session_id":"session-1","subscription_ttl_millis":120000}}}""",
        )
        val connector = FakeConnector(streams = listOf(stream))
        val connected = CountDownLatch(1)
        val delay = BlockingDelay()
        val client = LivePipelineClient(
            connector = connector,
            profileProvider = ::profile,
            userAgent = "NyTD-Test-UA",
            clock = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC),
            delay = delay,
        )
        val topics = (1..22).map { id -> "/tweet_engagement/$id" }.toSet()

        val connection = client.open(account("account-1", "1"), topics) { event ->
            if (event is LivePipelineEvent.Connected) {
                connected.countDown()
            }
        }
        try {
            assertTrue(connected.await(1, TimeUnit.SECONDS))
            assertEquals(1, connector.connectRequests.size)
            assertEquals(20, connector.connectRequests.single().url.queryParameter("topic")!!.split(',').size)
            assertEquals(1, connector.updateRequests.size)
            val body = URLDecoder.decode(
                connector.updateRequests.single().bodyText(),
                StandardCharsets.UTF_8.name(),
            )
            assertTrue(body.contains("tweet_engagement/8"))
            assertTrue(body.contains("tweet_engagement/9"))
            assertFalse(body.contains("tweet_engagement/1,"))
        } finally {
            connection.close()
        }
        assertTrue(stream.closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun reconnectsWithBoundedBackoffAndStopsOnFourHundreds() {
        val connector = FakeConnector(
            connectionFailures = ArrayDequeCompat(
                listOf(
                    LivePipelineHttpException(500, "temporary"),
                    LivePipelineHttpException(401, "unauthorized"),
                ),
            ),
        )
        val delay = RecordingDelay()
        val errors = mutableListOf<LivePipelineEvent.Error>()
        val stopped = CountDownLatch(2)
        val client = LivePipelineClient(
            connector = connector,
            profileProvider = ::profile,
            userAgent = "NyTD-Test-UA",
            delay = delay,
        )

        val connection = client.open(account("account-1", "1"), setOf("/tweet_engagement/1")) { event ->
            if (event is LivePipelineEvent.Error) {
                synchronized(errors) { errors += event }
                stopped.countDown()
            }
        }
        try {
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
        } finally {
            connection.close()
        }

        assertEquals(2, connector.connectRequests.size)
        assertEquals(listOf(Duration.ofMillis(500)), delay.pauses)
        assertEquals(listOf(500, 401), errors.map { error -> error.statusCode })
    }

    @Test
    fun renewsEveryTopicBeforeTheNativeSessionTtlExpires() {
        val clock = MutableClock(Instant.parse("2026-08-29T00:00:00Z"))
        val stream = BlockingStream(
            """{"topic":"/system/config","payload":{"config":{"session_id":"session-ttl","subscription_ttl_millis":120000}}}""",
        )
        val connector = FakeConnector(streams = listOf(stream))
        val delay = OneShotAdvancingDelay(clock)
        val client = LivePipelineClient(
            connector = connector,
            profileProvider = ::profile,
            userAgent = "NyTD-Test-UA",
            clock = clock,
            delay = delay,
        )

        val connection = client.open(account("account-1", "1"), setOf("/tweet_engagement/1")) { }
        try {
            waitUntil { connector.updateRequests.size == 1 }
            assertEquals(Duration.ofSeconds(100), delay.pauses.first())
            val body = URLDecoder.decode(
                connector.updateRequests.single().bodyText(),
                StandardCharsets.UTF_8.name(),
            )
            assertTrue(body.contains("/tweet_engagement/1"))
        } finally {
            connection.close()
        }
    }

    private fun profile() = XApiProfile(
        graphqlBaseUrl = "https://x.com/i/api/graphql",
        featureKeys = emptyList(),
        featureDefaults = emptyMap(),
        operations = mapOf(
            "homeForYou" to XApiProfile.GraphQlOperation(
                operationId = "operation-id",
                operationName = "HomeTimeline",
                type = XApiProfile.OperationType.QUERY,
                featureKeys = emptyList(),
                fieldToggles = emptyList(),
            ),
        ),
        restEndpoints = mapOf(
            "livePipelineEvents" to "/live_pipeline/events",
            "livePipelineUpdateSubscriptions" to "/1.1/live_pipeline/update_subscriptions",
        ),
    )

    private fun account(accountId: String, userId: String) = AccountSecrets(
        accountId, userId, "user$userId", "User $userId", "bearer-$accountId", "auth-$accountId", "csrf-$accountId", "profile-$accountId",
    )

    private class FakeConnector(
        streams: List<LivePipelineStream> = emptyList(),
        private val connectionFailures: ArrayDequeCompat<LivePipelineHttpException> = ArrayDequeCompat(emptyList()),
    ) : LivePipelineConnector {
        private val streams = ArrayDequeCompat(streams)
        val connectRequests = mutableListOf<Request>()
        val updateRequests = mutableListOf<Request>()

        override fun connect(request: Request): LivePipelineStream {
            synchronized(connectRequests) { connectRequests += request }
            connectionFailures.removeFirstOrNull()?.let { throw it }
            return streams.removeFirstOrNull() ?: BlockingStream()
        }

        override fun updateSubscriptions(request: Request) {
            synchronized(updateRequests) { updateRequests += request }
        }
    }

    private class BlockingStream(
        private vararg val lines: String,
    ) : LivePipelineStream {
        val closed = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun readDataLines(consumer: (String) -> Unit) {
            lines.forEach(consumer)
            release.await()
        }

        override fun close() {
            release.countDown()
            closed.countDown()
        }
    }

    private class RecordingDelay : LivePipelineDelay {
        val pauses = mutableListOf<Duration>()

        override fun pause(duration: Duration) {
            pauses += duration
        }
    }

    private class BlockingDelay : LivePipelineDelay {
        override fun pause(duration: Duration) {
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LivePipelineHttpException(0, "interrupted", exception)
            }
        }
    }

    private class OneShotAdvancingDelay(
        private val clock: MutableClock,
    ) : LivePipelineDelay {
        val pauses = mutableListOf<Duration>()
        private val subsequentWait = CountDownLatch(1)

        override fun pause(duration: Duration) {
            pauses += duration
            if (pauses.size == 1) {
                clock.advance(duration)
                return
            }
            try {
                subsequentWait.await()
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LivePipelineHttpException(0, "interrupted", exception)
            }
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock =
            if (zone == ZoneOffset.UTC) this else Clock.fixed(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) {
                return
            }
            Thread.sleep(5)
        }
        throw AssertionError("condition was not met")
    }

    private fun Request.bodyText(): String {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return buffer.readUtf8()
    }

    private class ArrayDequeCompat<T>(values: List<T>) {
        private val values = ArrayDeque(values)

        fun removeFirstOrNull(): T? = if (values.isEmpty()) null else values.removeFirst()
    }
}
