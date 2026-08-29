package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.data.AccountSecrets
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
import okhttp3.HttpUrl.Companion.toHttpUrl

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
        val normalizedLanguage = language.trim().replace('_', '-').lowercase(Locale.ROOT)
        require(normalizedLanguage.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*"))) {
            "表示言語の形式が不正です。"
        }
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
            ApiResponse(response.code, response.body.string())
        }
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("REST ${endpointKey}の通信に失敗しました。", cause = exception)
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
    )

    private companion object {
        const val MAX_TRANSACTION_ATTEMPTS = 2
        const val SIGNATURE_ERROR_CODE = 344
        const val TRANSACTION_HEADER = "X-Client-Transaction-Id"
    }
}
