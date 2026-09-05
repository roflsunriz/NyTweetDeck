package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.PostTranslationResult
import dev.nytweetdeck.android.model.Translation
import dev.nytweetdeck.android.model.TranslationHealth
import dev.nytweetdeck.android.model.TranslationOrigin
import dev.nytweetdeck.android.model.TranslationSkipReason
import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTranslationRepositoryTest {
    @Test
    fun usesMatchingNativePretranslationWithoutCallingAnyExternalTranslator() {
        val endpoint = RecordingEndpoint { call ->
            throw AssertionError("X endpoint must not be called: $call")
        }
        val repository = PostTranslationRepository(endpoint)

        val result = repository.translate(
            account = account("account-1"),
            postId = "123",
            sourceLanguage = "en_US",
            targetLanguage = "ja",
            preTranslated = Translation(
                text = "保存済み翻訳",
                sourceLanguage = "en",
                targetLanguage = "ja-JP",
                provider = "Grok",
            ),
        ) as PostTranslationResult.Translated

        assertEquals(TranslationOrigin.X_PRETRANSLATED, result.origin)
        assertEquals("保存済み翻訳", result.translation.text)
        assertEquals("en-us", result.translation.sourceLanguage)
        assertEquals("ja", result.translation.targetLanguage)
        assertEquals("X", result.translation.provider)
        assertTrue(endpoint.calls.isEmpty())
    }

    @Test
    fun skipsUndeterminedAndSameLanguagesAndRejectsInvalidTargetsBeforeNetwork() {
        val endpoint = RecordingEndpoint { call -> success(call.postId, "translated") }
        val repository = PostTranslationRepository(endpoint)

        val undetermined = repository.translate(account("account-1"), "123", "und", "ja")
        val sameLanguage = repository.translate(account("account-1"), "123", "ja-JP", "ja")

        assertEquals(
            TranslationSkipReason.UNDETERMINED_SOURCE_LANGUAGE,
            (undetermined as PostTranslationResult.Skipped).reason,
        )
        assertEquals(
            TranslationSkipReason.SAME_LANGUAGE,
            (sameLanguage as PostTranslationResult.Skipped).reason,
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.translate(account("account-1"), "123", "en", "und")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.translate(account("account-1"), "not-a-post-id", "en", "ja")
        }
        assertTrue(endpoint.calls.isEmpty())
    }

    @Test
    fun cachesPerAccountAndRejectsStreamErrors() {
        val endpoint = RecordingEndpoint { call -> success(call.postId, "翻訳-${call.accountId}") }
        val repository = PostTranslationRepository(endpoint)

        val first = repository.translate(account("account-a"), "123", "en", "ja")
        val cached = repository.translate(account("account-a"), "123", "en", "ja")
        val isolated = repository.translate(account("account-b"), "123", "en", "ja")

        assertEquals("翻訳-account-a", (first as PostTranslationResult.Translated).translation.text)
        assertEquals("翻訳-account-a", (cached as PostTranslationResult.Translated).translation.text)
        assertEquals("翻訳-account-b", (isolated as PostTranslationResult.Translated).translation.text)
        assertEquals(2, endpoint.calls.size)
        assertEquals("X", endpoint.calls[0].translationSource)
        assertEquals("ja", endpoint.calls[0].targetLanguage)
        assertEquals(1, repository.health("account-a").cacheHits)

        val mismatched = PostTranslationRepository(
            RecordingEndpoint { AuthenticatedRestClient.RestResult("""{"error":{"message":"failed"}}""", null, null) },
        )
        assertThrows(dev.nytweetdeck.android.model.PostTranslationException::class.java) {
            mismatched.translate(account("account-a"), "123", "en", "ja")
        }
    }

    @Test
    fun retriesRetryableResponsesAtMostFourTimesWithDeterministicBackoff() {
        val actions = ArrayDeque<(EndpointCall) -> AuthenticatedRestClient.RestResult>()
        actions += { throw restFailure(statusCode = 408) }
        actions += { throw restFailure(statusCode = 425) }
        actions += { throw restFailure(statusCode = 503) }
        actions += { call -> success(call.postId, "完了") }
        val endpoint = RecordingEndpoint { call -> actions.removeFirst().invoke(call) }
        val delay = RecordingDelay()
        val repository = PostTranslationRepository(endpoint, Clock.systemUTC(), delay)

        val result = repository.translate(account("account-1"), "123", "en", "ja")
        val health = repository.health("account-1")

        assertEquals("完了", (result as PostTranslationResult.Translated).translation.text)
        assertEquals(4, endpoint.calls.size)
        assertEquals(
            listOf(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
            delay.pauses,
        )
        assertEquals(4L, health.upstreamRequests)
        assertEquals(1L, health.upstreamSuccesses)
        assertEquals(25.0, health.upstreamSuccessRate)
        assertEquals(1L, health.upstreamServerErrors)
    }

    @Test
    fun retriesTransportFailuresAndRecordsTheirHealthImpact() {
        val attempts = AtomicInteger()
        val endpoint = RecordingEndpoint { call ->
            if (attempts.incrementAndGet() == 1) {
                throw IllegalStateException("fixture transport failure")
            }
            success(call.postId, "回復")
        }
        val delay = RecordingDelay()
        val repository = PostTranslationRepository(endpoint, Clock.systemUTC(), delay)

        val result = repository.translate(account("account-1"), "123", "en", "ja")

        assertEquals("回復", (result as PostTranslationResult.Translated).translation.text)
        assertEquals(2, endpoint.calls.size)
        assertEquals(listOf(Duration.ofSeconds(1)), delay.pauses)
        assertEquals(1L, repository.health("account-1").transportErrors)
    }

    @Test
    fun capsRetryAfterAtOneHourAndWaitsForTheFivePercentReserveReset() {
        val clock = MutableClock(Instant.parse("2026-08-29T10:00:00Z"))
        val delay = AdvancingDelay(clock)
        val retryActions = ArrayDeque<(EndpointCall) -> AuthenticatedRestClient.RestResult>()
        retryActions += {
            throw restFailure(
                statusCode = 429,
                retryAfterSeconds = 7_200,
                rateLimit = rateLimit(100, 0, clock.instant().plusSeconds(7_200)),
            )
        }
        retryActions += { call -> success(call.postId, "再試行成功") }
        val retryRepository = PostTranslationRepository(
            RecordingEndpoint { call -> retryActions.removeFirst().invoke(call) },
            clock,
            delay,
        )

        retryRepository.translate(account("account-1"), "123", "en", "ja")

        assertEquals(listOf(Duration.ofHours(1)), delay.pauses)
        assertEquals(1L, retryRepository.health("account-1").rateLimitedResponses)

        val reserveClock = MutableClock(Instant.parse("2026-08-29T10:00:00Z"))
        val reserveDelay = AdvancingDelay(reserveClock)
        val reserveEndpoint = RecordingEndpoint { call ->
            if (call.postId == "123") {
                success(call.postId, "最初", rateLimit(100, 5, reserveClock.instant().plusSeconds(600)))
            } else {
                success(call.postId, "次")
            }
        }
        val reserveRepository = PostTranslationRepository(reserveEndpoint, reserveClock, reserveDelay)

        reserveRepository.translate(account("account-1"), "123", "en", "ja")
        reserveRepository.translate(account("account-1"), "124", "en", "ja")

        assertEquals(listOf(Duration.ofMinutes(10)), reserveDelay.pauses)
        assertEquals(1L, reserveRepository.health("account-1").deferredRequests)
        assertEquals(5, reserveRepository.health("account-1").rateLimitRemaining)
    }

    @Test
    fun joinsSameInflightRequestAndLimitsDifferentUpstreamRequestsToTwo() {
        val sameStarted = CountDownLatch(1)
        val sameRelease = CountDownLatch(1)
        val sameEndpoint = RecordingEndpoint { call ->
            sameStarted.countDown()
            assertTrue(sameRelease.await(1, TimeUnit.SECONDS))
            success(call.postId, "同時翻訳")
        }
        val sameRepository = PostTranslationRepository(sameEndpoint)
        val first = CompletableFuture.supplyAsync {
            sameRepository.translate(account("account-1"), "123", "en", "ja")
        }
        assertTrue(sameStarted.await(1, TimeUnit.SECONDS))
        val second = CompletableFuture.supplyAsync {
            sameRepository.translate(account("account-1"), "123", "en", "ja")
        }
        try {
            waitUntil { sameRepository.health("account-1").joinedRequests == 1L }
            assertEquals(1, sameEndpoint.calls.size)
        } finally {
            sameRelease.countDown()
        }
        assertEquals("同時翻訳", ((first.get(1, TimeUnit.SECONDS)) as PostTranslationResult.Translated).translation.text)
        assertEquals("同時翻訳", ((second.get(1, TimeUnit.SECONDS)) as PostTranslationResult.Translated).translation.text)

        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val thirdStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val limitedEndpoint = RecordingEndpoint { call ->
            val current = active.incrementAndGet()
            maximumActive.updateMax(current)
            if (limitedEndpointCallNumber.incrementAndGet() == 3) {
                thirdStarted.countDown()
            }
            started.countDown()
            try {
                assertTrue(release.await(1, TimeUnit.SECONDS))
                success(call.postId, "翻訳-${call.postId}")
            } finally {
                active.decrementAndGet()
            }
        }
        val limitedRepository = PostTranslationRepository(limitedEndpoint)
        val futures = listOf("201", "202", "203").map { postId ->
            CompletableFuture.supplyAsync {
                limitedRepository.translate(account("account-1"), postId, "en", "ja")
            }
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse(thirdStarted.await(100, TimeUnit.MILLISECONDS))
            assertEquals(2, limitedEndpoint.calls.size)
        } finally {
            release.countDown()
        }
        futures.forEach { future -> future.get(1, TimeUnit.SECONDS) }
        assertEquals(2, maximumActive.get())
    }

    @Test
    fun keepsOnlyOneThousandLeastRecentlyUsedTranslations() {
        val endpoint = RecordingEndpoint { call -> success(call.postId, "翻訳-${call.postId}") }
        val repository = PostTranslationRepository(endpoint)

        (1..1_001).forEach { postId ->
            repository.translate(account("account-1"), postId.toString(), "en", "ja")
        }
        repository.translate(account("account-1"), "1", "en", "ja")

        assertEquals(1_000, repository.health("account-1").cacheEntries)
        assertEquals(1_002, endpoint.calls.size)
    }

    private fun account(id: String) = AccountSecrets(
        id, id, "user$id", "User $id", "bearer", "auth", "csrf", "profile-$id",
    )

    private fun success(
        postId: String,
        translation: String,
        rateLimit: AuthenticatedRestClient.RateLimitInfo? = null,
    ) = AuthenticatedRestClient.RestResult(
        body = """{"result":{"text":"$translation"}}""",
        rateLimit = rateLimit,
        retryAfterSeconds = null,
    )

    private fun rateLimit(
        limit: Int,
        remaining: Int,
        resetAt: Instant,
    ) = AuthenticatedRestClient.RateLimitInfo(limit, remaining, resetAt)

    private fun restFailure(
        statusCode: Int,
        retryAfterSeconds: Long? = null,
        rateLimit: AuthenticatedRestClient.RateLimitInfo? = null,
    ) = AuthenticatedRestClient.RestRequestException(
        endpointKey = "translatePost",
        statusCode = statusCode,
        retryAfterSeconds = retryAfterSeconds,
        rateLimit = rateLimit,
    )

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) {
                return
            }
            Thread.sleep(5)
        }
        throw AssertionError("condition was not met")
    }

    private class RecordingEndpoint(
        private val behavior: (EndpointCall) -> AuthenticatedRestClient.RestResult,
    ) : XPostTranslationEndpoint {
        val calls = mutableListOf<EndpointCall>()

        override fun translatePost(
            account: AccountSecrets,
            postId: String,
            translationSource: String,
            targetLanguage: String,
        ): AuthenticatedRestClient.RestResult {
            val call = EndpointCall(account.accountId, postId, translationSource, targetLanguage)
            synchronized(calls) {
                calls += call
            }
            require(translationSource == "X") { "外部翻訳は許可されていません。" }
            return behavior(call)
        }
    }

    private data class EndpointCall(
        val accountId: String,
        val postId: String,
        val translationSource: String,
        val targetLanguage: String,
    )

    private class RecordingDelay : TranslationDelay {
        val pauses = mutableListOf<Duration>()

        override fun pause(duration: Duration) {
            pauses += duration
        }
    }

    private class AdvancingDelay(
        private val clock: MutableClock,
    ) : TranslationDelay {
        val pauses = mutableListOf<Duration>()

        override fun pause(duration: Duration) {
            pauses += duration
            clock.advance(duration)
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = if (zone == ZoneOffset.UTC) this else Clock.fixed(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private fun AtomicInteger.updateMax(candidate: Int) {
        while (true) {
            val current = get()
            if (candidate <= current || compareAndSet(current, candidate)) {
                return
            }
        }
    }

    private companion object {
        val limitedEndpointCallNumber = AtomicInteger()
    }
}
