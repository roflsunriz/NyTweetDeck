package dev.nytweetdeck.android.model

import java.time.Instant

data class PostTranslation(
    val postId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val text: String,
    val provider: String = "X",
)

enum class TranslationOrigin {
    X_PRETRANSLATED,
    X_ON_DEMAND,
}

enum class TranslationSkipReason {
    MISSING_SOURCE_LANGUAGE,
    INVALID_SOURCE_LANGUAGE,
    UNDETERMINED_SOURCE_LANGUAGE,
    SAME_LANGUAGE,
}

sealed interface PostTranslationResult {
    data class Translated(
        val translation: PostTranslation,
        val origin: TranslationOrigin,
    ) : PostTranslationResult

    data class Skipped(
        val reason: TranslationSkipReason,
        val sourceLanguage: String?,
        val targetLanguage: String,
    ) : PostTranslationResult
}

data class TranslationHealth(
    val requests: Long,
    val upstreamRequests: Long,
    val upstreamSuccesses: Long,
    val upstreamSuccessRate: Double?,
    val recentSuccessRate: Double?,
    val cacheHits: Long,
    val joinedRequests: Long,
    val deferredRequests: Long,
    val rateLimitedResponses: Long,
    val upstreamServerErrors: Long,
    val transportErrors: Long,
    val rateLimit: Int?,
    val rateLimitRemaining: Int?,
    val rateLimitResetAt: Instant?,
    val cacheEntries: Int,
    val inFlightRequests: Int,
)

class PostTranslationException(
    message: String,
    val statusCode: Int?,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class TranslationLoadStatus {
    IDLE,
    LOADING,
    READY,
    FAILED,
    SKIPPED,
}

data class PostTranslationUiState(
    val status: TranslationLoadStatus = TranslationLoadStatus.IDLE,
    val translation: PostTranslation? = null,
    val showOriginal: Boolean = false,
    val retryAfterSeconds: Long? = null,
)

data class TranslationCandidate(
    val postId: String,
    val sourceLanguage: String?,
    val preTranslated: Translation?,
    val text: String,
)
