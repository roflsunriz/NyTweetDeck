package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostTranslation
import dev.nytweetdeck.android.model.PostTranslationException
import dev.nytweetdeck.android.model.PostTranslationResult
import dev.nytweetdeck.android.model.Translation
import dev.nytweetdeck.android.model.TranslationHealth
import dev.nytweetdeck.android.model.TranslationOrigin
import dev.nytweetdeck.android.model.TranslationSkipReason
import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Boundary for the one allowed translation source: X's native translatePost REST endpoint. */
fun interface XPostTranslationEndpoint {
    fun translatePost(
        account: AccountSecrets,
        postId: String,
        translationSource: String,
        targetLanguage: String,
    ): AuthenticatedRestClient.RestResult
}

/** Injectable waiting boundary used for rate-limit waits and retry backoff. */
fun interface TranslationDelay {
    fun pause(duration: Duration)
}

class PostTranslationRepository(
    private val endpoint: XPostTranslationEndpoint,
    private val clock: Clock = Clock.systemUTC(),
    private val delay: TranslationDelay = SystemTranslationDelay,
) {
    constructor(
        restClient: AuthenticatedRestClient,
        clock: Clock = Clock.systemUTC(),
        delay: TranslationDelay = SystemTranslationDelay,
    ) : this(
        endpoint = XPostTranslationEndpoint { account, postId, source, target ->
            restClient.translatePost(account, postId, source, target)
        },
        clock = clock,
        delay = delay,
    )

    private val memory = TranslationMemory<CacheKey, PostTranslation>(MAX_CACHE_ENTRIES)
    private val accountRateLimits = ConcurrentHashMap<String, RateLimitState>()
    private val latestRateLimit = AtomicReference<RateLimitState?>(null)
    private val upstreamSlots = Semaphore(MAX_CONCURRENT_UPSTREAM, true)
    private val requests = AtomicLong()
    private val cacheHits = AtomicLong()
    private val joinedRequests = AtomicLong()
    private val deferredRequests = AtomicLong()
    private val upstreamRequests = AtomicLong()
    private val upstreamSuccesses = AtomicLong()
    private val rateLimitedResponses = AtomicLong()
    private val upstreamServerErrors = AtomicLong()
    private val transportErrors = AtomicLong()
    private val recentOutcomes = ArrayDeque<Boolean>()

    fun translate(
        account: AccountSecrets,
        post: Post,
        targetLanguage: String,
    ): PostTranslationResult = translate(
        account = account,
        postId = post.id,
        sourceLanguage = post.language,
        targetLanguage = targetLanguage,
        preTranslated = post.preTranslated,
    )

    fun translate(
        account: AccountSecrets,
        postId: String,
        sourceLanguage: String?,
        targetLanguage: String,
        preTranslated: Translation? = null,
    ): PostTranslationResult {
        requests.incrementAndGet()
        validatePostId(postId)
        val target = normalizeRequiredLanguage(targetLanguage, "翻訳先言語")
        val source = normalizeSourceLanguage(sourceLanguage, target)
        if (source is SourceLanguage.Skip) {
            return PostTranslationResult.Skipped(source.reason, source.value, target)
        }
        val normalizedSource = (source as SourceLanguage.Known).value
        if (sameBaseLanguage(normalizedSource, target)) {
            return PostTranslationResult.Skipped(TranslationSkipReason.SAME_LANGUAGE, normalizedSource, target)
        }
        matchingPreTranslation(preTranslated, target)?.let { translation ->
            return PostTranslationResult.Translated(
                translation = PostTranslation(postId, normalizedSource, target, translation, X_TRANSLATION_SOURCE),
                origin = TranslationOrigin.X_PRETRANSLATED,
            )
        }

        val key = CacheKey(account.accountId, postId, normalizedSource, target)
        val translation = memory.getOrLoad(
            key,
            onHit = { cacheHits.incrementAndGet() },
            onJoin = { joinedRequests.incrementAndGet() },
        ) { fetchFromX(account, postId, normalizedSource, target) }
        return PostTranslationResult.Translated(translation, TranslationOrigin.X_ON_DEMAND)
    }

    fun health(accountId: String? = null): TranslationHealth {
        val rateLimit = accountId?.let(accountRateLimits::get) ?: latestRateLimit.get()
        val cacheSize = memory.size
        return TranslationHealth(
            requests = requests.get(),
            upstreamRequests = upstreamRequests.get(),
            upstreamSuccesses = upstreamSuccesses.get(),
            upstreamSuccessRate = percentage(upstreamSuccesses.get(), upstreamRequests.get()),
            recentSuccessRate = recentSuccessRate(),
            cacheHits = cacheHits.get(),
            joinedRequests = joinedRequests.get(),
            deferredRequests = deferredRequests.get(),
            rateLimitedResponses = rateLimitedResponses.get(),
            upstreamServerErrors = upstreamServerErrors.get(),
            transportErrors = transportErrors.get(),
            rateLimit = rateLimit?.limit,
            rateLimitRemaining = rateLimit?.remaining,
            rateLimitResetAt = rateLimit?.resetAt,
            cacheEntries = cacheSize,
            inFlightRequests = memory.inFlight,
        )
    }

    private fun fetchFromX(
        account: AccountSecrets,
        postId: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): PostTranslation {
        var lastFailure: PostTranslationException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            awaitReservedRateLimit(account.accountId)
            try {
                acquireSlot()
                try {
                    upstreamRequests.incrementAndGet()
                    val response = endpoint.translatePost(
                        account = account,
                        postId = postId,
                        translationSource = X_TRANSLATION_SOURCE,
                        targetLanguage = targetLanguage,
                    )
                    updateRateLimit(account.accountId, response.rateLimit)
                    val translation = parse(response.body, postId, sourceLanguage, targetLanguage)
                    upstreamSuccesses.incrementAndGet()
                    recordRecentOutcome(true)
                    return translation
                } finally {
                    upstreamSlots.release()
                }
            } catch (exception: AuthenticatedRestClient.RestRequestException) {
                lastFailure = failureFromRest(account.accountId, exception)
                recordRecentOutcome(false)
                if (isRetryable(exception.statusCode) && attempt < MAX_ATTEMPTS) {
                    delay.pause(retryDelay(exception, attempt))
                    continue
                }
                throw lastFailure
            } catch (exception: PostTranslationException) {
                recordRecentOutcome(false)
                throw exception
            } catch (exception: RuntimeException) {
                transportErrors.incrementAndGet()
                lastFailure = PostTranslationException(
                    message = "X翻訳の通信に失敗しました。",
                    statusCode = 0,
                    cause = exception,
                )
                recordRecentOutcome(false)
                if (attempt < MAX_ATTEMPTS) {
                    delay.pause(defaultRetryDelay(attempt))
                    continue
                }
                throw lastFailure
            }
        }
        throw checkNotNull(lastFailure)
    }

    internal fun parse(
        body: String,
        postId: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): PostTranslation {
        return dev.nytweetdeck.android.xapi.parseLiveTranslation(body, postId, sourceLanguage, targetLanguage)
    }

    private fun matchingPreTranslation(preTranslated: Translation?, targetLanguage: String): String? {
        if (preTranslated == null || preTranslated.text.isBlank()) {
            return null
        }
        val preTranslationTarget = normalizeLanguageOrNull(preTranslated.targetLanguage) ?: return null
        return preTranslated.text.takeIf { sameBaseLanguage(preTranslationTarget, targetLanguage) }
    }

    private fun normalizeSourceLanguage(value: String?, targetLanguage: String): SourceLanguage {
        if (value.isNullOrBlank()) {
            return SourceLanguage.Skip(TranslationSkipReason.MISSING_SOURCE_LANGUAGE, null)
        }
        val normalized = normalizeLanguageOrNull(value)
            ?: return SourceLanguage.Skip(TranslationSkipReason.INVALID_SOURCE_LANGUAGE, value)
        if (baseLanguage(normalized) in UNDETERMINED_LANGUAGES) {
            return SourceLanguage.Skip(TranslationSkipReason.UNDETERMINED_SOURCE_LANGUAGE, normalized)
        }
        if (baseLanguage(targetLanguage) in UNDETERMINED_LANGUAGES) {
            throw IllegalArgumentException("翻訳先言語にundなどは指定できません。")
        }
        return SourceLanguage.Known(normalized)
    }

    private fun normalizeRequiredLanguage(value: String, label: String): String {
        val normalized = normalizeLanguageOrNull(value)
            ?: throw IllegalArgumentException("${label}の形式が不正です。")
        if (baseLanguage(normalized) in UNDETERMINED_LANGUAGES) {
            throw IllegalArgumentException("${label}にundなどは指定できません。")
        }
        return normalized
    }

    private fun normalizeLanguageOrNull(value: String): String? {
        val normalized = value.trim().replace('_', '-').lowercase(Locale.ROOT)
        return normalized.takeIf(LANGUAGE_PATTERN::matches)
    }

    private fun awaitReservedRateLimit(accountId: String) {
        val state = accountRateLimits[accountId] ?: return
        val blockedUntil = state.blockedUntil ?: return
        val now = clock.instant()
        if (!blockedUntil.isAfter(now)) {
            return
        }
        deferredRequests.incrementAndGet()
        delay.pause(Duration.between(now, blockedUntil).coerceAtMost(MAX_DELAY))
    }

    private fun updateRateLimit(accountId: String, rateLimit: AuthenticatedRestClient.RateLimitInfo?) {
        if (rateLimit == null) {
            return
        }
        val reserve = rateLimit.limit?.let { limit ->
            kotlin.math.max(1, kotlin.math.ceil(limit * RATE_LIMIT_RESERVE_RATIO).toInt())
        }
        val blockedUntil = if (reserve != null &&
            rateLimit.remaining != null &&
            rateLimit.resetAt != null &&
            rateLimit.remaining <= reserve
        ) {
            rateLimit.resetAt
        } else {
            null
        }
        val state = RateLimitState(
            limit = rateLimit.limit,
            remaining = rateLimit.remaining,
            resetAt = rateLimit.resetAt,
            blockedUntil = blockedUntil,
        )
        accountRateLimits[accountId] = state
        latestRateLimit.set(state)
    }

    private fun failureFromRest(
        accountId: String,
        exception: AuthenticatedRestClient.RestRequestException,
    ): PostTranslationException {
        when {
            exception.statusCode == 429 -> {
                rateLimitedResponses.incrementAndGet()
                updateRateLimitFromFailure(accountId, exception)
            }
            exception.statusCode >= 500 -> upstreamServerErrors.incrementAndGet()
            exception.statusCode == 0 -> transportErrors.incrementAndGet()
        }
        return PostTranslationException(
            message = "X翻訳に失敗しました。",
            statusCode = exception.statusCode,
            retryAfterSeconds = exception.retryAfterSeconds?.coerceIn(0, MAX_DELAY.seconds),
            cause = exception,
        )
    }

    private fun updateRateLimitFromFailure(
        accountId: String,
        exception: AuthenticatedRestClient.RestRequestException,
    ) {
        val now = clock.instant()
        val retryAfter = exception.retryAfterSeconds?.coerceIn(0, MAX_DELAY.seconds)
        val blockedUntil = retryAfter?.let { seconds -> now.plusSeconds(seconds) }
            ?: exception.rateLimit?.resetAt
        val previous = accountRateLimits[accountId]
        val state = RateLimitState(
            limit = exception.rateLimit?.limit ?: previous?.limit,
            remaining = exception.rateLimit?.remaining ?: 0,
            resetAt = exception.rateLimit?.resetAt ?: blockedUntil,
            blockedUntil = blockedUntil,
        )
        accountRateLimits[accountId] = state
        latestRateLimit.set(state)
    }

    private fun retryDelay(
        exception: AuthenticatedRestClient.RestRequestException,
        attempt: Int,
    ): Duration {
        exception.retryAfterSeconds?.let { seconds ->
            return Duration.ofSeconds(seconds.coerceIn(0, MAX_DELAY.seconds))
        }
        exception.rateLimit?.resetAt?.let { resetAt ->
            val wait = Duration.between(clock.instant(), resetAt)
            if (!wait.isNegative) {
                return wait.coerceAtMost(MAX_DELAY)
            }
        }
        return defaultRetryDelay(attempt)
    }

    private fun defaultRetryDelay(attempt: Int): Duration = Duration.ofSeconds(
        (1L shl (attempt - 1)).coerceAtMost(MAX_DELAY.seconds),
    )

    private fun acquireSlot() {
        try {
            upstreamSlots.acquire()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PostTranslationException("X翻訳の待機が中断されました。", 0, cause = exception)
        }
    }

    private fun isRetryable(statusCode: Int): Boolean = statusCode == 0 ||
        statusCode == 408 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode in 500..599

    private fun recordRecentOutcome(success: Boolean) {
        synchronized(recentOutcomes) {
            recentOutcomes.addLast(success)
            while (recentOutcomes.size > RECENT_OUTCOME_LIMIT) {
                recentOutcomes.removeFirst()
            }
        }
    }

    private fun recentSuccessRate(): Double? = synchronized(recentOutcomes) {
        if (recentOutcomes.isEmpty()) {
            null
        } else {
            percentage(recentOutcomes.count { value -> value }.toLong(), recentOutcomes.size.toLong())
        }
    }

    private fun validatePostId(postId: String) {
        require(POST_ID.matches(postId)) { "ポストIDの形式が不正です。" }
    }

    private fun baseLanguage(language: String): String = language.substringBefore('-')

    private fun sameBaseLanguage(first: String, second: String): Boolean =
        baseLanguage(first) == baseLanguage(second)

    private fun percentage(successes: Long, total: Long): Double? =
        if (total == 0L) null else kotlin.math.round(successes * 1_000.0 / total) / 10.0

    private sealed interface SourceLanguage {
        data class Known(val value: String) : SourceLanguage

        data class Skip(
            val reason: TranslationSkipReason,
            val value: String?,
        ) : SourceLanguage
    }

    private data class CacheKey(
        val accountId: String,
        val postId: String,
        val sourceLanguage: String,
        val targetLanguage: String,
    )

    private data class RateLimitState(
        val limit: Int?,
        val remaining: Int?,
        val resetAt: Instant?,
        val blockedUntil: Instant?,
    )

    private object SystemTranslationDelay : TranslationDelay {
        override fun pause(duration: Duration) {
            if (duration.isZero || duration.isNegative) {
                return
            }
            try {
                Thread.sleep(duration.toMillis())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PostTranslationException("X翻訳の待機が中断されました。", 0, cause = exception)
            }
        }
    }

    private companion object {
        const val X_TRANSLATION_SOURCE = "X"
        const val MAX_CACHE_ENTRIES = 1_000
        const val MAX_CONCURRENT_UPSTREAM = 2
        const val MAX_ATTEMPTS = 4
        const val RECENT_OUTCOME_LIMIT = 100
        const val RATE_LIMIT_RESERVE_RATIO = 0.05
        val MAX_DELAY: Duration = Duration.ofHours(1)
        val POST_ID = Regex("[0-9]{1,19}")
        val LANGUAGE_PATTERN = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
        val UNDETERMINED_LANGUAGES = setOf("und", "zxx")
    }
}
