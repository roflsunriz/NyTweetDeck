package dev.nytweetdeck.android.xapi.live

import dev.nytweetdeck.android.data.AccountSecrets
import java.time.Instant
import kotlinx.serialization.json.JsonElement

data class LivePipelineSessionConfig(
    val sessionId: String,
    val subscriptionTtlMilliseconds: Long,
)

data class LiveEngagementCounts(
    val replyCount: Long?,
    val repostCount: Long?,
    val quoteCount: Long?,
    val likeCount: Long?,
    val bookmarkCount: Long?,
    val viewCount: Long?,
)

sealed interface ParsedLivePipelineEvent {
    val topic: String

    data class SystemConfig(
        override val topic: String,
        val config: LivePipelineSessionConfig,
    ) : ParsedLivePipelineEvent

    data class TweetEngagement(
        override val topic: String,
        val postId: String?,
        val counts: LiveEngagementCounts,
    ) : ParsedLivePipelineEvent

    data class DirectMessageUpdate(
        override val topic: String,
        val userId: String?,
        val payload: JsonElement,
    ) : ParsedLivePipelineEvent

    data class DirectMessageTyping(
        override val topic: String,
        val userId: String?,
        val payload: JsonElement,
    ) : ParsedLivePipelineEvent

    data class LiveContent(
        override val topic: String,
        val entityId: String?,
        val payload: JsonElement,
    ) : ParsedLivePipelineEvent
}

enum class LivePipelineErrorKind {
    CONNECTION,
    PROTOCOL,
    SUBSCRIPTION,
}

sealed interface LivePipelineEvent {
    val accountId: String
    val occurredAt: Instant

    data class Connected(
        override val accountId: String,
        val sessionId: String,
        val subscriptionTtlMilliseconds: Long,
        override val occurredAt: Instant,
    ) : LivePipelineEvent

    data class Engagement(
        override val accountId: String,
        val postId: String?,
        val counts: LiveEngagementCounts,
        override val occurredAt: Instant,
    ) : LivePipelineEvent

    data class DirectMessageUpdate(
        override val accountId: String,
        val userId: String?,
        val payload: JsonElement,
        override val occurredAt: Instant,
    ) : LivePipelineEvent

    data class DirectMessageTyping(
        override val accountId: String,
        val userId: String?,
        val payload: JsonElement,
        override val occurredAt: Instant,
    ) : LivePipelineEvent

    data class LiveContent(
        override val accountId: String,
        val entityId: String?,
        val payload: JsonElement,
        override val occurredAt: Instant,
    ) : LivePipelineEvent

    data class Error(
        override val accountId: String,
        val kind: LivePipelineErrorKind,
        val statusCode: Int?,
        val retryDelayMilliseconds: Long?,
        override val occurredAt: Instant,
    ) : LivePipelineEvent
}

interface LivePipelineConnection : AutoCloseable {
    override fun close()
}

fun interface LivePipelineSessionClient {
    fun open(
        account: AccountSecrets,
        topics: Set<String>,
        eventConsumer: (LivePipelineEvent) -> Unit,
    ): LivePipelineConnection
}

data class LivePipelineSubscriptionStatus(
    val accountId: String,
    val connected: Boolean,
    val topicCount: Int,
    val lastErrorKind: LivePipelineErrorKind?,
    val lastEventAt: Instant?,
)
