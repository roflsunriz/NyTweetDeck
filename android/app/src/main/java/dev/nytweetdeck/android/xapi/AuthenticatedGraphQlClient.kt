package dev.nytweetdeck.android.xapi

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class XSessionCredentials(
    val bearerToken: String,
    val authToken: String,
    val csrfToken: String,
)

fun interface GraphQlExecutor {
    fun execute(
        credentials: XSessionCredentials,
        purpose: String,
        variables: Map<String, Any?>,
        language: String,
    ): String
}

class AuthenticatedGraphQlClient(
    client: OkHttpClient,
    private val profileProvider: () -> XApiProfile,
    private val userAgent: String,
    private val transactionIdService: XClientTransactionIdService =
        XClientTransactionIdService(client, userAgent),
) : GraphQlExecutor {
    constructor(
        client: OkHttpClient,
        profile: XApiProfile,
        userAgent: String,
    ) : this(client, { profile }, userAgent)

    constructor(
        client: OkHttpClient,
        profile: XApiProfile,
        userAgent: String,
        transactionIdService: XClientTransactionIdService,
    ) : this(client, { profile }, userAgent, transactionIdService)

    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    override fun execute(
        credentials: XSessionCredentials,
        purpose: String,
        variables: Map<String, Any?>,
        language: String,
    ): String {
        validateCredentials(credentials)
        val normalizedLanguage = normalizeLanguage(language)
        val profile = profileProvider()
        val operation = profile.requireOperation(purpose)
        val features = profile.featuresFor(operation)
        val toggles = selectFieldToggles(purpose, operation)
        val base = profile.graphqlBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment(operation.operationId)
            .addPathSegment(operation.operationName)
            .build()
        val variablesJson = mapToJson(variables)
        val featuresJson = mapToJson(features)
        val togglesJson = mapToJson(toggles)

        val requestBuilder = Request.Builder()
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .header("Cookie", "auth_token=${credentials.authToken}; ct0=${credentials.csrfToken}")
            .header("X-CSRF-Token", credentials.csrfToken)
            .header("X-Twitter-Auth-Type", "OAuth2Session")
            .header("X-Twitter-Active-User", "yes")
            .header("X-Twitter-Client-Language", normalizedLanguage)
            .header("Accept-Language", normalizedLanguage)
            .header("Origin", "https://x.com")
            .header("Referer", "https://x.com/")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")

        when {
            operation.type == XApiProfile.OperationType.MUTATION -> {
                val body = buildJsonObject {
                    put("variables", variablesJson)
                    put("queryId", operation.operationId)
                    if (operation.featureKeys.isNotEmpty()) {
                        put("features", featuresJson)
                    }
                }.toString().toRequestBody(JSON_MEDIA_TYPE)
                requestBuilder.url(base).post(body)
            }
            purpose == "search" -> {
                val body = buildJsonObject {
                    put("variables", variablesJson)
                    put("features", featuresJson)
                    put("fieldToggles", togglesJson)
                }.toString().toRequestBody(JSON_MEDIA_TYPE)
                requestBuilder.url(base).post(body)
            }
            else -> {
                val url = base.newBuilder()
                    .addQueryParameter("features", featuresJson.toString())
                    .addQueryParameter("fieldToggles", togglesJson.toString())
                    .addQueryParameter("variables", variablesJson.toString())
                    .build()
                requestBuilder.url(url).get()
            }
        }

        val unsignedRequest = requestBuilder.build()
        for (attempt in 0 until MAX_TRANSACTION_ATTEMPTS) {
            val request = withTransactionHeader(unsignedRequest, "GraphQL ${purpose}")
            val response = executeRequest(request, purpose)
            if (isSignatureRejected(request, response)) {
                if (attempt == 0) {
                    transactionIdService.invalidate()
                    continue
                }
                throw XApiException("GraphQL ${purpose}のWeb署名を更新できませんでした。", 502)
            }
            if (response.statusCode !in 200..299) {
                throw XApiException("GraphQL ${purpose}に失敗しました。HTTP ${response.statusCode}", response.statusCode)
            }
            rejectTerminalGraphQlErrors(response.body, purpose)
            return response.body
        }
        throw XApiException("GraphQL ${purpose}のWeb署名を更新できませんでした。", 502)
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

    private fun executeRequest(request: Request, purpose: String): ApiResponse = try {
        client.newCall(request).execute().use { response ->
            ApiResponse(response.code, response.body.string())
        }
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("GraphQL ${purpose}の通信に失敗しました。", cause = exception)
    }

    private fun isSignatureRejected(
        request: Request,
        response: ApiResponse,
    ): Boolean =
        request.header(TRANSACTION_HEADER) != null &&
        (response.statusCode == 404 || hasGraphQlErrorCode(response.body, SIGNATURE_ERROR_CODE))

    private fun hasGraphQlErrorCode(body: String, expectedCode: Int): Boolean = try {
        val errors = Json.parseToJsonElement(body).jsonObject["errors"] as? JsonArray ?: return false
        errors.any { error ->
            ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull == expectedCode
        }
    } catch (_: Exception) {
        false
    }

    private fun rejectTerminalGraphQlErrors(body: String, purpose: String) {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (exception: Exception) {
            throw XApiException("GraphQL応答を解析できません。", cause = exception)
        }
        val errors = root["errors"] as? JsonArray
        val data = root["data"]
        if (!errors.isNullOrEmpty() && (data == null || data is JsonNull || data == JsonObject(emptyMap()))) {
            throw XApiException("GraphQL ${purpose}がエラーを返しました。", 502)
        }
    }

    private fun selectFieldToggles(
        purpose: String,
        operation: XApiProfile.GraphQlOperation,
    ): Map<String, Boolean> {
        val keys = operation.fieldToggles.ifEmpty { DEFAULT_FIELD_TOGGLES.keys.toList() }
        val includeArticle = purpose == "postDetail" || purpose == "conversation"
        return keys.associateWith { key ->
            when (key) {
                "withArticleRichContentState", "withArticlePlainText" -> includeArticle
                "withArticleSummaryText" -> true
                else -> DEFAULT_FIELD_TOGGLES[key] ?: false
            }
        }
    }

    private fun normalizeLanguage(value: String): String {
        val normalized = value.trim().replace('_', '-')
        require(LANGUAGE_PATTERN.matches(normalized)) { "表示言語の形式が不正です。" }
        return normalized.lowercase(Locale.ROOT)
    }

    private fun validateCredentials(credentials: XSessionCredentials) {
        require(credentials.bearerToken.isNotBlank()) { "Web Bearerが空です。" }
        require(credentials.authToken.isNotBlank()) { "auth_tokenが空です。" }
        require(credentials.csrfToken.isNotBlank()) { "ct0が空です。" }
    }

    private fun mapToJson(values: Map<String, Any?>): JsonObject = JsonObject(
        values.mapValues { (_, value) -> value.toJsonElement() },
    )

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
            require(key is String) { "GraphQL map keyは文字列である必要があります。" }
            key to value.toJsonElement()
        })
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        else -> throw IllegalArgumentException("GraphQL変数の型が不正です。")
    }

    private companion object {
        const val MAX_TRANSACTION_ATTEMPTS = 2
        const val SIGNATURE_ERROR_CODE = 344
        const val TRANSACTION_HEADER = "X-Client-Transaction-Id"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val LANGUAGE_PATTERN = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
        val DEFAULT_FIELD_TOGGLES = mapOf(
            "withPayments" to false,
            "withAuxiliaryUserLabels" to false,
            "withArticleRichContentState" to false,
            "withArticlePlainText" to false,
            "withArticleSummaryText" to true,
            "withArticleVoiceOver" to false,
            "withGrokAnalyze" to false,
            "withDisallowedReplyControls" to false,
        )
    }

    private data class ApiResponse(
        val statusCode: Int,
        val body: String,
    )
}
