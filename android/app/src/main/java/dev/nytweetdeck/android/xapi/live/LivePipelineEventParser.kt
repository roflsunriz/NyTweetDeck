package dev.nytweetdeck.android.xapi.live

import dev.nytweetdeck.android.xapi.XApiException
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LivePipelineEventParser(
    private val json: Json = Json { isLenient = false },
) {
    fun parse(body: String): ParsedLivePipelineEvent = try {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("Live Pipelineイベントがobjectではありません。")
        val topic = root.text("topic")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Live Pipelineイベントにtopicがありません。")
        val payload = root["payload"] as? JsonObject
            ?: throw IllegalArgumentException("Live Pipelineイベントにpayloadがありません。")
        when {
            topic == SYSTEM_CONFIG_TOPIC && payload["config"] is JsonObject -> {
                ParsedLivePipelineEvent.SystemConfig(topic, parseSessionConfig(payload["config"] as JsonObject))
            }
            payload["tweet_engagement"] != null -> ParsedLivePipelineEvent.TweetEngagement(
                topic = topic,
                postId = entityId(topic),
                counts = parseEngagement(payload["tweet_engagement"]),
            )
            payload["dm_update"] != null -> ParsedLivePipelineEvent.DirectMessageUpdate(
                topic = topic,
                userId = entityId(topic),
                payload = requireNotNull(payload["dm_update"]),
            )
            payload["dm_typing"] != null -> ParsedLivePipelineEvent.DirectMessageTyping(
                topic = topic,
                userId = entityId(topic),
                payload = requireNotNull(payload["dm_typing"]),
            )
            payload["live_content"] != null -> ParsedLivePipelineEvent.LiveContent(
                topic = topic,
                entityId = entityId(topic),
                payload = requireNotNull(payload["live_content"]),
            )
            else -> throw IllegalArgumentException("未確認のLive Pipelineイベント種別です。")
        }
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("Live Pipelineイベントを解析できません。", cause = exception)
    }

    private fun parseSessionConfig(config: JsonObject): LivePipelineSessionConfig {
        val sessionId = config.text("session_id")?.takeIf { value ->
            value.isNotBlank() && value.length <= MAX_SESSION_ID_LENGTH && value.none(Char::isISOControl)
        } ?: throw IllegalArgumentException("Live PipelineのセッションIDが不正です。")
        val ttl = config.long("subscription_ttl_millis")
            ?.takeIf { value -> value in MIN_TTL_MILLISECONDS..MAX_TTL_MILLISECONDS }
            ?: DEFAULT_TTL_MILLISECONDS
        return LivePipelineSessionConfig(sessionId, ttl)
    }

    private fun parseEngagement(payload: JsonElement?): LiveEngagementCounts {
        val values = payload as? JsonObject
        return LiveEngagementCounts(
            replyCount = values?.long("reply_count"),
            repostCount = values?.long("retweet_count"),
            quoteCount = values?.long("quote_count"),
            likeCount = values?.long("favorite_count"),
            bookmarkCount = values?.long("bookmark_count"),
            viewCount = values?.long("view_count"),
        )
    }

    private fun entityId(topic: String): String? = topic.substringAfterLast('/', "")
        .takeIf(String::isNotBlank)

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(name: String): Long? = text(name)?.toLongOrNull()

    private companion object {
        const val SYSTEM_CONFIG_TOPIC = "/system/config"
        const val DEFAULT_TTL_MILLISECONDS = 120_000L
        const val MIN_TTL_MILLISECONDS = 1_000L
        val MAX_TTL_MILLISECONDS = Duration.ofDays(1).toMillis()
        const val MAX_SESSION_ID_LENGTH = 500
    }
}
