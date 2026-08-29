package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.data.AccountSecrets
import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedRestClientTest {
    @Test
    fun officialRestRequestCarriesTransactionHeaderAndRefreshesOnceAfter404() {
        val interceptor = SequenceInterceptor(
            ResponseSpec(404, """{"errors":[{"code":344}]}"""),
            ResponseSpec(200, """{"ok":true}"""),
        )
        val transactions = RecordingTransactionIdService()
        val client = client(profile(), interceptor, transactions)

        val result = client.get(account(), "lookup", mapOf("screen_name" to "alice"), "ja-JP")

        assertEquals("""{"ok":true}""", result)
        assertEquals(2, interceptor.requests.size)
        assertEquals("transaction-1", interceptor.requests[0].header(TRANSACTION_HEADER))
        assertEquals("transaction-2", interceptor.requests[1].header(TRANSACTION_HEADER))
        assertEquals("ja-jp", interceptor.requests[0].header("X-Twitter-Client-Language"))
        assertEquals("alice", interceptor.requests[0].url.queryParameter("screen_name"))
        assertEquals(1, transactions.invalidations)
        assertEquals(
            listOf("GET /1.1/users/show.json", "GET /1.1/users/show.json"),
            transactions.generated,
        )
    }

    @Test
    fun transactionGenerationFailureIsRetriedWithoutSendingRestRequest() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"ok":true}"""))
        val transactions = RecordingTransactionIdService(failuresBeforeSuccess = 2)
        val client = client(profile(), interceptor, transactions)

        val error = assertThrows(XApiException::class.java) {
            client.get(account(), "lookup", mapOf("screen_name" to "alice"))
        }

        assertEquals(2, transactions.generated.size)
        assertEquals(1, transactions.invalidations)
        assertTrue(interceptor.requests.isEmpty())
        assertTrue(requireNotNull(error.message).contains("Web署名を生成できません"))
        assertFalse(requireNotNull(error.message).contains("fixture transaction failure"))
    }

    @Test
    fun customRestProfilesKeepTheExistingUnsignedRequestBehavior() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"ok":true}"""))
        val transactions = RecordingTransactionIdService()
        val client = client(
            profile(restBaseUrl = "https://example.invalid/api"),
            interceptor,
            transactions,
        )

        client.get(account(), "lookup", mapOf("screen_name" to "alice"))

        val request = require(interceptor.requests.size == 1) { "expected one request" }
            .let { interceptor.requests.single() }
        assertNull(request.header(TRANSACTION_HEADER))
        assertTrue(transactions.generated.isEmpty())
    }

    @Test
    fun translatePostExpandsOnlyTheOfficialPathVariablesAndReturnsRateLimitMetadata() {
        val resetAt = Instant.parse("2026-08-29T12:00:00Z")
        val interceptor = SequenceInterceptor(
            ResponseSpec(
                statusCode = 200,
                body = """{"id_str":"123","translation":"こんにちは"}""",
                headers = mapOf(
                    "x-rate-limit-limit" to "100",
                    "x-rate-limit-remaining" to "95",
                    "x-rate-limit-reset" to resetAt.epochSecond.toString(),
                    "Retry-After" to "12",
                ),
            ),
        )
        val transactions = RecordingTransactionIdService()
        val client = client(profile(), interceptor, transactions)

        val result = client.translatePost(account(), "123", "X", "ja_JP")

        val request = require(interceptor.requests.size == 1) { "expected one request" }
            .let { interceptor.requests.single() }
        assertTrue(request.url.encodedPath.contains("tweetId=123"))
        assertTrue(request.url.encodedPath.contains("translationSource=Some(X)"))
        assertFalse(request.url.encodedPath.contains('{'))
        assertEquals("ja-jp", request.header("X-Twitter-Client-Language"))
        assertEquals("transaction-1", request.header(TRANSACTION_HEADER))
        assertEquals("こんにちは", result.body.substringAfter("translation\":\"").substringBefore('"'))
        assertNotNull(result.rateLimit)
        assertEquals(100, result.rateLimit?.limit)
        assertEquals(95, result.rateLimit?.remaining)
        assertEquals(resetAt, result.rateLimit?.resetAt)
        assertEquals(12L, result.retryAfterSeconds)
        assertThrows(IllegalArgumentException::class.java) {
            client.translatePost(account(), "123", "Google", "ja")
        }
        assertEquals(1, interceptor.requests.size)
    }

    @Test
    fun translatePostExposesRetryAfterAndRateLimitHeadersOnFailures() {
        val resetAt = Instant.parse("2026-08-29T12:00:00Z")
        val client = client(
            profile(),
            SequenceInterceptor(
                ResponseSpec(
                    statusCode = 429,
                    body = """{"errors":[{"code":88}]}""",
                    headers = mapOf(
                        "Retry-After" to "7200",
                        "x-rate-limit-limit" to "100",
                        "x-rate-limit-remaining" to "0",
                        "x-rate-limit-reset" to resetAt.epochSecond.toString(),
                    ),
                ),
            ),
            RecordingTransactionIdService(),
        )

        val error = assertThrows(AuthenticatedRestClient.RestRequestException::class.java) {
            client.translatePost(account(), "123", "X", "ja")
        }

        assertEquals(429, error.statusCode)
        assertEquals(7200L, error.retryAfterSeconds)
        assertEquals(0, error.rateLimit?.remaining)
        assertEquals(resetAt, error.rateLimit?.resetAt)
    }

    private fun client(
        profile: XApiProfile,
        interceptor: SequenceInterceptor,
        transactions: RecordingTransactionIdService,
    ) = AuthenticatedRestClient(
        client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        profileProvider = { profile },
        userAgent = "NyTD-Test-UA",
        transactionIdService = transactions,
    )

    private fun profile(restBaseUrl: String = "https://api.twitter.com") = XApiProfile(
        graphqlBaseUrl = "https://x.com/i/api/graphql",
        featureKeys = emptyList(),
        featureDefaults = emptyMap(),
        operations = mapOf(
            "homeForYou" to XApiProfile.GraphQlOperation(
                operationId = "operation-id",
                operationName = "HomeTimeline",
                type = XApiProfile.OperationType.QUERY,
                featureKeys = emptyList(),
                fieldToggles = emptyList(),
            ),
        ),
        restBaseUrl = restBaseUrl,
        restEndpoints = mapOf(
            "lookup" to "/1.1/users/show.json",
            "translatePost" to "/1.1/strato/column/None/tweetId={postId}," +
                "destinationLanguage=None,translationSource=Some({translationSource})," +
                "feature=None,timeout=None,onlyCached=None/translation/service/translateTweet",
        ),
    )

    private fun account() = AccountSecrets.webSession(
        accountId = "account-1",
        userId = "42",
        username = "alice",
        displayName = "Alice",
        webBearerToken = "bearer-fixture",
        authToken = "auth-fixture",
        csrfToken = "csrf-fixture",
    )

    private class RecordingTransactionIdService(
        private var failuresBeforeSuccess: Int = 0,
    ) : XClientTransactionIdService(
        XClientTransactionIdService.AssetFetcher { _, _ -> "" },
    ) {
        val generated = mutableListOf<String>()
        var invalidations = 0

        override fun generate(method: String, requestUrl: HttpUrl): String {
            generated += "$method ${requestUrl.encodedPath}"
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess -= 1
                throw XApiException("fixture transaction failure", 502)
            }
            return "transaction-${generated.size}"
        }

        override fun invalidate() {
            invalidations += 1
        }
    }

    private class SequenceInterceptor(
        vararg responses: ResponseSpec,
    ) : Interceptor {
        private val responses = responses.toList()
        private var nextResponse = 0
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val response = responses[nextResponse.coerceAtMost(responses.lastIndex)]
            nextResponse += 1
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(response.statusCode)
                .message(if (response.statusCode in 200..299) "OK" else "Rejected")
                .body(response.body.toResponseBody("application/json".toMediaType()))
                .apply {
                    response.headers.forEach { (name, value) -> addHeader(name, value) }
                }
                .build()
        }
    }

    private data class ResponseSpec(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val TRANSACTION_HEADER = "X-Client-Transaction-Id"
    }
}
