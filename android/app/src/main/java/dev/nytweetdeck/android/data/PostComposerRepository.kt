package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.ComposerSubmission
import dev.nytweetdeck.android.model.ComposerPlace
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.ComposerWebClient
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class PostComposerRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: TimelineResponseParser = TimelineResponseParser(),
    private val webClient: ComposerWebClient? = null,
) {
    fun submitAdvanced(
        account: AccountSecrets,
        submission: ComposerSubmission,
        replyToPostId: String? = null,
        quotePostId: String? = null,
        language: String = "ja",
    ): Post? {
        validateAdvanced(submission, replyToPostId, quotePostId)
        val client = webClient
        val mediaIds = submission.mediaUris.map { uri ->
            requireNotNull(client) { "メディアアップロードが利用できません。" }.upload(account, uri, language)
        }
        val cardUri = submission.poll?.let { poll ->
            requireNotNull(client) { "投票カードを作成できません。" }.createPoll(account, poll, language)
        }
        val credentials = XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken)
        if (submission.scheduledAt != null) {
            val body = graphQlExecutor.execute(credentials, "schedulePost", scheduledVariables(submission, mediaIds), language)
            val id = runCatching {
                Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                    ?.get("tweet")?.jsonObject?.get("rest_id")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            require(id != null && POST_ID.matches(id)) { "予約投稿の確認IDがありません。" }
            return null
        }
        val response = graphQlExecutor.execute(credentials, "createPost",
            advancedVariables(submission, mediaIds, cardUri, replyToPostId, quotePostId), language)
        return parser.parse(response).posts.firstOrNull()
            ?: error("投稿応答に作成済みポストがありません。")
    }

    fun searchPlaces(account: AccountSecrets, query: String, language: String = "ja"): List<ComposerPlace> =
        requireNotNull(webClient) { "場所検索が利用できません。" }.searchPlaces(account, query, language)

    internal fun advancedVariables(
        submission: ComposerSubmission,
        mediaIds: List<String>,
        cardUri: String?,
        replyToPostId: String? = null,
        quotePostId: String? = null,
    ): Map<String, Any> {
        validateAdvanced(submission, replyToPostId, quotePostId)
        return buildMap {
            put("tweet_text", submission.text.trim())
            put("nullcast", false)
            put("includeCommunityTweetRelationship", false)
            put("includeTweetVisibilityNudge", true)
            replyToPostId?.let { put("reply", mapOf(
                "in_reply_to_tweet_id" to it,
                "exclude_reply_user_ids" to emptyList<String>(),
            )) }
            quotePostId?.let { put("attachment_url", "https://twitter.com/i/status/$it") }
            if (mediaIds.isNotEmpty()) put("media", mapOf(
                "media_entities" to mediaIds.map { mapOf("media_id" to it, "tagged_users" to emptyList<String>()) },
                "possibly_sensitive" to false,
            ))
            cardUri?.let { put("card_uri", it) }
            submission.place?.let { place -> put("geo", placeVariables(place)) }
            disclosureVariables(submission).takeIf(Map<String, Any>::isNotEmpty)?.let {
                put("content_disclosure", it)
            }
        }
    }

    internal fun scheduledVariables(submission: ComposerSubmission, mediaIds: List<String>): Map<String, Any> {
        validateAdvanced(submission, null, null)
        val at = requireNotNull(submission.scheduledAt)
        val request = buildMap<String, Any> {
            put("status", submission.text.trim())
            put("media_ids", mediaIds)
            put("exclude_reply_user_ids", emptyList<String>())
            put("thread_tweets", emptyList<Any>())
            disclosureVariables(submission).takeIf(Map<String, Any>::isNotEmpty)?.let {
                put("content_disclosure_options", it)
            }
        }
        return mapOf("post_tweet_request" to request, "execute_at" to at.epochSecond)
    }

    private fun validateAdvanced(submission: ComposerSubmission, replyToPostId: String?, quotePostId: String?) {
        require(submission.text.length <= MAX_POST_LENGTH &&
            (submission.text.isNotBlank() || submission.mediaUris.isNotEmpty() || submission.poll != null)) {
            "本文、画像、GIF、投票のいずれかを指定してください。"
        }
        require(submission.mediaUris.size <= 4) { "画像は最大4枚まで選択できます。" }
        require(replyToPostId == null || POST_ID.matches(replyToPostId)) { "返信先ポストIDの形式が不正です。" }
        require(quotePostId == null || POST_ID.matches(quotePostId)) { "引用先ポストIDの形式が不正です。" }
        require(replyToPostId == null || quotePostId == null) { "返信と引用は同時に指定できません。" }
        submission.poll?.let { poll ->
            require(poll.choices.size in 2..4 && poll.durationMinutes in 5..10_080 &&
                poll.choices.all { it.isNotBlank() && it.length <= 25 }) { "投票の選択肢または期間が不正です。" }
            require(submission.mediaUris.isEmpty() && replyToPostId == null && quotePostId == null) {
                "投票は画像・GIF・返信・引用と同時に指定できません。"
            }
        }
        submission.place?.let { place ->
            require(place.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
                "場所IDの形式が不正です。"
            }
            require(place.geoSearchRequestId == null ||
                place.geoSearchRequestId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
                "場所検索IDの形式が不正です。"
            }
        }
        submission.scheduledAt?.let { scheduled ->
            require(scheduled.isAfter(java.time.Instant.now().plusSeconds(120)) &&
                scheduled.isBefore(java.time.Instant.now().plusSeconds(365L * 24 * 3600))) {
                "予約日時は2分後から1年以内に指定してください。"
            }
            require(submission.poll == null && submission.place == null &&
                replyToPostId == null && quotePostId == null) {
                "予約投稿は投票・場所・返信・引用と組み合わせられません。"
            }
        }
    }

    private fun disclosureVariables(submission: ComposerSubmission): Map<String, Any> = buildMap {
        if (submission.paidPartnership) put("advertising_disclosure", mapOf("is_paid_promotion" to true))
        if (submission.aiGenerated) put("ai_generated_disclosure", mapOf(
            "has_ai_generated_media" to true,
            "ai_generated_detection_source" to "UserDeclared",
        ))
    }

    private fun placeVariables(place: ComposerPlace): Map<String, String> = buildMap {
        put("place_id", place.id)
        place.geoSearchRequestId?.let { put("geo_search_request_id", it) }
    }

    fun submit(
        account: AccountSecrets,
        text: String,
        replyToPostId: String? = null,
        quotePostId: String? = null,
        language: String = "ja",
    ): Post {
        val variables = variables(text, replyToPostId, quotePostId)
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "createPost",
            variables,
            language,
        )
        return parser.parse(body).posts.firstOrNull()
            ?: error("投稿応答に作成済みポストがありません。")
    }

    fun delete(account: AccountSecrets, postId: String, language: String = "ja") {
        require(POST_ID.matches(postId)) { "削除対象ポストIDの形式が不正です。" }
        graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "deletePost",
            mapOf("tweet_id" to postId),
            language,
        )
    }

    internal fun variables(
        text: String,
        replyToPostId: String? = null,
        quotePostId: String? = null,
    ): Map<String, Any> {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty() && normalizedText.length <= MAX_POST_LENGTH) {
            "ポスト本文は1〜4000文字で指定してください。"
        }
        require(replyToPostId == null || POST_ID.matches(replyToPostId)) {
            "返信先ポストIDの形式が不正です。"
        }
        require(quotePostId == null || POST_ID.matches(quotePostId)) {
            "引用先ポストIDの形式が不正です。"
        }
        require(replyToPostId == null || quotePostId == null) {
            "返信と引用は同時に指定できません。"
        }
        return buildMap {
            put("tweet_text", normalizedText)
            put("nullcast", false)
            put("includeCommunityTweetRelationship", false)
            put("includeTweetVisibilityNudge", true)
            replyToPostId?.let { postId ->
                put(
                    "reply",
                    mapOf(
                        "in_reply_to_tweet_id" to postId,
                        "exclude_reply_user_ids" to emptyList<String>(),
                    ),
                )
            }
            quotePostId?.let { postId ->
                put("attachment_url", "https://twitter.com/i/status/$postId")
            }
        }
    }

    private companion object {
        const val MAX_POST_LENGTH = 4000
        val POST_ID = Regex("[0-9]{1,19}")
    }
}
