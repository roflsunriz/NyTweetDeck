package dev.nytweetdeck.android.xapi

import android.content.Context
import android.net.Uri
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.model.ComposerPlace
import dev.nytweetdeck.android.model.ComposerPoll
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** X Webセッションで画像・GIF、投票カード、場所候補を扱う。 */
class ComposerWebClient(
    client: OkHttpClient,
    context: Context,
    private val userAgent: String,
    private val transactionIdService: XClientTransactionIdService,
    private val uploadUrl: HttpUrl = "https://upload.x.com/i/media/upload.json".toHttpUrl(),
    private val pollUrl: HttpUrl = "https://caps.x.com/v2/cards/create.json".toHttpUrl(),
    private val placesUrl: HttpUrl = "https://api.x.com/1.1/geo/places.json".toHttpUrl(),
) {
    private val contentResolver = context.applicationContext.contentResolver
    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .build()

    fun upload(account: AccountSecrets, uri: Uri, language: String = "ja"): String {
        val bytes = contentResolver.openInputStream(uri)?.use { source ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = source.read(chunk)
                if (count < 0) break
                if (output.size() + count > MAX_GIF_BYTES) throw IllegalArgumentException("画像またはGIFのサイズが上限を超えています。")
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("画像またはGIFを読み込めません。")
        val type = mediaType(bytes)
        if (type != "image/gif" && bytes.size > MAX_IMAGE_BYTES) {
            throw IllegalArgumentException("画像のサイズが上限を超えています。")
        }
        val init = execute(account, uploadUrl.newBuilder()
            .addQueryParameter("command", "INIT")
            .addQueryParameter("total_bytes", bytes.size.toString())
            .addQueryParameter("media_type", type).build(),
            "POST", ByteArray(0).toRequestBody(), language)
        val mediaId = init.string("media_id_string")
            ?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            ?: throw XApiException("Xが画像IDを返しませんでした。", 502)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("media", "upload", bytes.toRequestBody(type.toMediaType()))
            .build()
        execute(account, uploadUrl.newBuilder()
            .addQueryParameter("command", "APPEND")
            .addQueryParameter("media_id", mediaId)
            .addQueryParameter("segment_index", "0").build(),
            "POST", multipart, language)
        var response = execute(account, uploadUrl.newBuilder()
            .addQueryParameter("command", "FINALIZE")
            .addQueryParameter("media_id", mediaId).build(),
            "POST", ByteArray(0).toRequestBody(), language)
        repeat(20) {
            val processing = response.objectValue("processing_info")
            when (processing.string("state")) {
                null, "succeeded" -> return mediaId
                "failed" -> throw XApiException("XがGIFを処理できませんでした。", 502)
            }
            val waitSeconds = processing?.get("check_after_secs")?.jsonPrimitive?.intOrNull?.coerceIn(1, 5) ?: 1
            Thread.sleep(waitSeconds * 1_000L)
            response = execute(account, uploadUrl.newBuilder()
                .addQueryParameter("command", "STATUS")
                .addQueryParameter("media_id", mediaId).build(), "GET", null, language)
        }
        throw XApiException("GIFの処理が制限時間内に完了しませんでした。", 502)
    }

    fun createPoll(account: AccountSecrets, poll: ComposerPoll, language: String = "ja"): String {
        require(poll.choices.size in 2..4 && poll.durationMinutes in 5..10_080 &&
            poll.choices.all { it.isNotBlank() && it.length <= 25 }) {
            "投票の選択肢または期間が不正です。"
        }
        val card = pollCardData(poll)
        val response = execute(account, pollUrl, "POST",
            FormBody.Builder().add("card_data", card.toString()).build(), language)
        return response.string("card_uri")?.takeIf { response.string("status") == "OK" && it.isNotBlank() }
            ?: throw XApiException("Xが投票カードを作成できませんでした。", 502)
    }

    fun searchPlaces(account: AccountSecrets, query: String, language: String = "ja"): List<ComposerPlace> {
        val normalized = query.trim()
        require(normalized.length in 1..100) { "場所の検索語は1〜100文字で指定してください。" }
        val response = execute(account, placesUrl.newBuilder()
            .addQueryParameter("query_type", "tweet_compose_location")
            .addQueryParameter("search_term", normalized).build(), "GET", null, language)
        return parsePlaces(response)
    }

    private fun execute(account: AccountSecrets, url: HttpUrl, method: String,
        body: RequestBody?, language: String): JsonObject {
        val locale = language.takeIf { it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) } ?: "ja"
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer ${account.webBearerToken}")
            .header("Cookie", "auth_token=${account.authToken}; ct0=${account.csrfToken}")
            .header("X-CSRF-Token", account.csrfToken)
            .header("X-Twitter-Auth-Type", "OAuth2Session")
            .header("X-Twitter-Active-User", "yes")
            .header("X-Twitter-Client-Language", locale)
            .header("Accept-Language", locale)
            .header("Origin", "https://x.com")
            .header("Referer", "https://x.com/")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
        if (method == "POST") builder.post(requireNotNull(body)) else builder.get()
        val unsigned = builder.build()
        for (attempt in 0..1) {
            val request = unsigned.newBuilder()
                .header("X-Client-Transaction-Id", transactionIdService.generate(method, url)).build()
            val response = try {
                client.newCall(request).execute().use { result ->
                    result.code to result.body.string()
                }
            } catch (exception: IOException) {
                throw XApiException("Xの投稿作成通信に失敗しました。", cause = exception)
            }
            if ((response.first == 404 || response.second.contains("\"code\":344")) && attempt == 0) {
                transactionIdService.invalidate()
                continue
            }
            if (response.first !in 200..299) {
                throw XApiException("Xの投稿作成通信に失敗しました。HTTP ${response.first}", response.first)
            }
            if (response.second.isBlank()) return JsonObject(emptyMap())
            return Json.parseToJsonElement(response.second) as? JsonObject
                ?: throw XApiException("Xの投稿作成応答が不正です。", 502)
        }
        throw XApiException("Xの投稿作成Web署名を更新できませんでした。", 502)
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private const val MAX_GIF_BYTES = 15 * 1024 * 1024

        internal fun parsePlaces(response: JsonObject): List<ComposerPlace> {
            val places = response["places"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return places.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val place = item.objectValue("place") ?: return@mapNotNull null
                val id = place.string("id") ?: return@mapNotNull null
                val name = place.string("full_name") ?: return@mapNotNull null
                ComposerPlace(id, name, place.string("country"), item.string("geo_search_request_id"))
            }.take(20)
        }

        internal fun pollCardData(poll: ComposerPoll): JsonObject = buildJsonObject {
            put("twitter:card", "poll${poll.choices.size}choice_text_only")
            put("twitter:api:api:endpoint", "1")
            put("twitter:long:duration_minutes", poll.durationMinutes)
            poll.choices.forEachIndexed { index, choice ->
                put("twitter:string:choice${index + 1}_label", choice.trim())
            }
        }

        internal fun mediaType(bytes: ByteArray): String {
            require(bytes.size >= 6) { "画像ファイルが空または不正です。" }
            return when {
                (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 255) == 216 &&
                    (bytes[2].toInt() and 255) == 255 -> "image/jpeg"
                bytes.size >= 8 && (bytes[0].toInt() and 255) == 137 &&
                    bytes[1] == 80.toByte() && bytes[2] == 78.toByte() && bytes[3] == 71.toByte() -> "image/png"
                bytes[0] == 71.toByte() && bytes[1] == 73.toByte() && bytes[2] == 70.toByte() &&
                    bytes[3] == 56.toByte() && (bytes[4] == 55.toByte() || bytes[4] == 57.toByte()) &&
                    bytes[5] == 97.toByte() -> "image/gif"
                else -> throw IllegalArgumentException("JPEG・PNG・GIF形式のみアップロードできます。")
            }
        }
    }
}

private fun JsonObject?.string(key: String): String? = this?.get(key)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.objectValue(key: String): JsonObject? = this?.get(key) as? JsonObject
