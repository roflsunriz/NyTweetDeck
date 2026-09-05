package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.data.AccountSecrets
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AuthenticatedRestClient(
    client: OkHttpClient,
    private val profileProvider: () -> XApiProfile,
    private val userAgent: String,
    private val transactionIdService: XClientTransactionIdService =
        XClientTransactionIdService(client, userAgent),
) {
    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    fun get(
        account: AccountSecrets,
        endpointKey: String,
        parameters: Map<String, String>,
        language: String = "ja",
    ): String {
        val profile = profileProvider()
        val path = profile.restEndpoints[endpointKey]
            ?: throw XApiException("X REST定義に${endpointKey}がありません。", 503)
        val base = profile.restBaseUrl.toHttpUrl()
        val urlBuilder = base.newBuilder().encodedPath(path)
        parameters.toSortedMap().forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z0-9_]{1,100}"))) { "RESTパラメーター名が不正です。" }
            require(value.length <= 1000) { "RESTパラメーターが長すぎます。" }
            urlBuilder.addQueryParameter(key, value)
        }
        val normalizedLanguage = normalizeLanguage(language)
        val unsignedRequest = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer ${account.webBearerToken}")
            .header("Cookie", "auth_token=${account.authToken}; ct0=${account.csrfToken}")
            .header("X-CSRF-Token", account.csrfToken)
            .header("X-Twitter-Auth-Type", "OAuth2Session")
            .header("X-Twitter-Active-User", "yes")
            .header("X-Twitter-Client-Language", normalizedLanguage)
            .header("Accept-Language", normalizedLanguage)
            .header("Origin", "https://x.com")
            .header("Referer", "https://x.com/")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()
        for (attempt in 0 until MAX_TRANSACTION_ATTEMPTS) {
            val request = withTransactionHeader(unsignedRequest, "REST ${endpointKey}")
            val response = executeRequest(request, endpointKey)
            if (isSignatureRejected(request, response)) {
                if (attempt == 0) {
                    transactionIdService.invalidate()
                    continue
                }
                throw XApiException("REST ${endpointKey}のWeb署名を更新できませんでした。", 502)
            }
            if (response.statusCode !in 200..299) {
                throw XApiException("REST ${endpointKey}に失敗しました。HTTP ${response.statusCode}", response.statusCode)
            }
            return response.body
        }
        throw XApiException("REST ${endpointKey}のWeb署名を更新できませんでした。", 502)
    }

    fun postForm(
        account: AccountSecrets,
        endpointKey: String,
        parameters: Map<String, String>,
        language: String = "ja",
    ): String {
        val profile = profileProvider()
        val path = profile.restEndpoints[endpointKey]
            ?: throw XApiException("X REST定義に${endpointKey}がありません。", 503)
        val form = FormBody.Builder().apply {
            parameters.toSortedMap().forEach { (key, value) ->
                require(key.matches(Regex("[A-Za-z0-9_]{1,100}"))) { "RESTパラメーター名が不正です。" }
                require(value.length <= 1000) { "RESTパラメーターが長すぎます。" }
                add(key, value)
            }
        }.build()
        val normalizedLanguage = normalizeLanguage(language)
        val unsignedRequest = Request.Builder()
            .url(profile.restBaseUrl.toHttpUrl().newBuilder().encodedPath(path).build())
            .header("Authorization", "Bearer ${account.webBearerToken}")
            .header("Cookie", "auth_token=${account.authToken}; ct0=${account.csrfToken}")
            .header("X-CSRF-Token", account.csrfToken)
            .header("X-Twitter-Auth-Type", "OAuth2Session")
            .header("X-Twitter-Active-User", "yes")
            .header("X-Twitter-Client-Language", normalizedLanguage)
            .header("Accept-Language", normalizedLanguage)
            .header("Origin", "https://x.com")
            .header("Referer", "https://x.com/")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .post(form)
            .build()
        for (attempt in 0 until MAX_TRANSACTION_ATTEMPTS) {
            val request = withTransactionHeader(unsignedRequest, "REST ${endpointKey}")
            val response = executeRequest(request, endpointKey)
            if (isSignatureRejected(request, response) && attempt == 0) {
                transactionIdService.invalidate()
                continue
            }
            if (response.statusCode !in 200..299) {
                throw XApiException("REST ${endpointKey}に失敗しました。HTTP ${response.statusCode}", response.statusCode)
            }
            return response.body
        }
        throw XApiException("REST ${endpointKey}のWeb署名を更新できませんでした。", 502)
    }

    /** Calls only X's native post translation route and returns its rate-limit metadata. */
    fun translatePost(
        account: AccountSecrets,
        postId: String,
        translationSource: String,
        language: String = "ja",
    ): RestResult {
        require(POST_ID.matches(postId)) { "ポストIDの形式が不正です。" }
        require(translationSource == X_TRANSLATION_SOURCE) { "翻訳元はXだけを指定できます。" }
        return translateLive(account, postId, "POST", language)
    }

    /** The official live Community Note route uses the request language header. */
    fun translateCommunityNote(account: AccountSecrets, noteId: String, language: String): RestResult {
        require(noteId.matches(Regex("[0-9]{1,24}"))) { "コミュニティノートIDの形式が不正です。" }
        return translateLive(account, noteId, "COMMUNITY_NOTE", language)
    }

    private fun translateLive(account: AccountSecrets, id: String, contentType: String, language: String): RestResult {
        val profile = profileProvider()
        val endpointKey = "grokTranslation"
        val path = profile.restEndpoints[endpointKey]
            ?: throw XApiException("X REST定義に${endpointKey}がありません。", 503)
        val target = normalizeLanguage(language)
        val fields = mutableMapOf("content_type" to JsonPrimitive(contentType), "id" to JsonPrimitive(id))
        if (contentType == "POST") fields["dst_lang"] = JsonPrimitive(target)
        val body = JsonObject(fields)
        val request = Request.Builder()
            .url(("https://x.com/i/api" + path).toHttpUrl())
            .header("Authorization", "Bearer ${account.webBearerToken}")
            .header("Cookie", "auth_token=${account.authToken}; ct0=${account.csrfToken}")
            .header("X-CSRF-Token", account.csrfToken)
            .header("X-Twitter-Auth-Type", "OAuth2Session")
            .header("X-Twitter-Active-User", "yes")
            .header("X-Twitter-Client-Language", target)
            .header("Accept-Language", target)
            .header("Origin", "https://x.com")
            .header("Referer", "https://x.com/")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return executeNativeTranslation(request, endpointKey)
    }

    private fun executeNativeTranslation(unsignedRequest: Request, endpointKey: String): RestResult {
        for (attempt in 0 until MAX_TRANSACTION_ATTEMPTS) {
            val request = try {
                withTransactionHeader(unsignedRequest, "REST ${endpointKey}")
            } catch (exception: Exception) {
                throw RestRequestException(
                    endpointKey = endpointKey,
                    statusCode = 0,
                    retryAfterSeconds = null,
                    rateLimit = null,
                    cause = exception,
                )
            }
            val response = executeTranslationRequest(request, endpointKey)
            val rateLimit = rateLimitInfo(response.headers)
            val retryAfter = retryAfterSeconds(response.headers)
            if (isSignatureRejected(request, response)) {
                if (attempt == 0) {
                    transactionIdService.invalidate()
                    continue
                }
                throw RestRequestException(
                    endpointKey = endpointKey,
                    statusCode = 502,
                    retryAfterSeconds = retryAfter,
                    rateLimit = rateLimit,
                )
            }
            if (response.statusCode !in 200..299) {
                throw RestRequestException(
                    endpointKey = endpointKey,
                    statusCode = response.statusCode,
                    retryAfterSeconds = retryAfter,
                    rateLimit = rateLimit,
                )
            }
            return RestResult(response.body, rateLimit, retryAfter)
        }
        throw RestRequestException(
            endpointKey = endpointKey,
            statusCode = 502,
            retryAfterSeconds = null,
            rateLimit = null,
        )
    }

    private fun withTransactionHeader(request: Request, scope: String): Request {
        if (!XClientTransactionIdService.supportsOfficialApiRequest(request.url)) {
            return request
        }
        val transactionId = generateTransactionId(request, scope)
        return request.newBuilder().header(TRANSACTION_HEADER, transactionId).build()
    }

    private fun generateTransactionId(request: Request, scope: String): String {
        var lastFailure: Exception? = null
        for (attempt in 0 until MAX_TRANSACTION_ATTEMPTS) {
            try {
                return transactionIdService.generate(request.method, request.url)
            } catch (exception: Exception) {
                lastFailure = exception
                if (attempt == 0) {
                    transactionIdService.invalidate()
                }
            }
        }
        throw XApiException("${scope}のWeb署名を生成できません。", 502, lastFailure)
    }

    private fun executeRequest(request: Request, endpointKey: String): ApiResponse = try {
        client.newCall(request).execute().use { response ->
            ApiResponse(response.code, response.body.string(), response.headers)
        }
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("REST ${endpointKey}の通信に失敗しました。", cause = exception)
    }

    private fun executeTranslationRequest(request: Request, endpointKey: String): ApiResponse = try {
        client.newCall(request).execute().use { response ->
            ApiResponse(response.code, response.body.string(), response.headers)
        }
    } catch (exception: Exception) {
        throw RestRequestException(
            endpointKey = endpointKey,
            statusCode = 0,
            retryAfterSeconds = null,
            rateLimit = null,
            cause = exception,
        )
    }

    private fun isSignatureRejected(request: Request, response: ApiResponse): Boolean =
        request.header(TRANSACTION_HEADER) != null &&
            (response.statusCode == 404 || hasXErrorCode(response.body, SIGNATURE_ERROR_CODE))

    private fun hasXErrorCode(body: String, expectedCode: Int): Boolean = try {
        val errors = Json.parseToJsonElement(body).jsonObject["errors"] as? JsonArray ?: return false
        errors.any { error ->
            ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull == expectedCode
        }
    } catch (_: Exception) {
        false
    }

    private data class ApiResponse(
        val statusCode: Int,
        val body: String,
        val headers: Headers,
    )

    data class RestResult(
        val body: String,
        val rateLimit: RateLimitInfo?,
        val retryAfterSeconds: Long?,
    )

    data class RateLimitInfo(
        val limit: Int?,
        val remaining: Int?,
        val resetAt: Instant?,
    )

    class RestRequestException(
        val endpointKey: String,
        val statusCode: Int,
        val retryAfterSeconds: Long?,
        val rateLimit: RateLimitInfo?,
        cause: Throwable? = null,
    ) : RuntimeException(
        if (statusCode == 0) {
            "REST ${endpointKey}の通信に失敗しました。"
        } else {
            "REST ${endpointKey}に失敗しました。HTTP ${statusCode}"
        },
        cause,
    )

    private fun normalizeLanguage(language: String): String {
        val normalizedLanguage = language.trim().replace('_', '-').lowercase(Locale.ROOT)
        require(normalizedLanguage.matches(LANGUAGE_PATTERN)) { "表示言語の形式が不正です。" }
        return normalizedLanguage
    }

    private fun rateLimitInfo(headers: Headers): RateLimitInfo? {
        val limit = headers["x-rate-limit-limit"].toNonNegativeIntOrNull()
        val remaining = headers["x-rate-limit-remaining"].toNonNegativeIntOrNull()
        val resetAt = headers["x-rate-limit-reset"].toEpochInstantOrNull()
        return if (limit == null && remaining == null && resetAt == null) {
            null
        } else {
            RateLimitInfo(limit, remaining, resetAt)
        }
    }

    private fun retryAfterSeconds(headers: Headers): Long? = headers["Retry-After"]
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { value -> value >= 0 }

    private fun String?.toNonNegativeIntOrNull(): Int? = this
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { value -> value in 0..Int.MAX_VALUE }
        ?.toInt()

    private fun String?.toEpochInstantOrNull(): Instant? = this
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { value -> value >= 0 }
        ?.let { value -> runCatching { Instant.ofEpochSecond(value) }.getOrNull() }

    private companion object {
        const val MAX_TRANSACTION_ATTEMPTS = 2
        const val SIGNATURE_ERROR_CODE = 344
        const val TRANSACTION_HEADER = "X-Client-Transaction-Id"
        const val X_TRANSLATION_SOURCE = "X"
        val POST_ID = Regex("[0-9]{1,19}")
        val LANGUAGE_PATTERN = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")
    }
}
