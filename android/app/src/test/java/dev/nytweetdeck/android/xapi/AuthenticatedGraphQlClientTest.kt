package dev.nytweetdeck.android.xapi

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedGraphQlClientTest {
    @Test
    fun officialQueryCarriesWebSessionAndTransactionHeaders() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"data":{"ok":true}}"""))
        val transactions = RecordingTransactionIdService()
        val client = client(profile(), interceptor, transactions)

        client.execute(
            credentials = credentials(),
            purpose = "homeForYou",
            variables = mapOf("count" to 20, "includePromotedContent" to false),
            language = "ja-JP",
        )

        val request = interceptor.singleRequest()
        assertEquals("Bearer bearer-fixture", request.header("Authorization"))
        assertEquals("csrf-fixture", request.header("X-CSRF-Token"))
        assertEquals("ja-jp", request.header("X-Twitter-Client-Language"))
        assertEquals("transaction-1", request.header(TRANSACTION_HEADER))
        assertNotNull(request.url.queryParameter("variables"))
        assertNotNull(request.url.queryParameter("features"))
        assertEquals(listOf("GET /i/api/graphql/operation-id/HomeTimeline"), transactions.generated)
    }

    @Test
    fun mutationCarriesTheHeaderAndRefreshesExactlyOnceForCode344() {
        val interceptor = SequenceInterceptor(
            ResponseSpec(200, """{"errors":[{"code":344}]}"""),
            ResponseSpec(200, """{"data":{"bookmark":true}}"""),
        )
        val transactions = RecordingTransactionIdService()
        val client = client(
            profile(
                operations = mapOf(
                    "bookmark" to operation(
                        id = "mutation-id",
                        name = "CreateBookmark",
                        type = XApiProfile.OperationType.MUTATION,
                    ),
                ),
            ),
            interceptor,
            transactions,
        )

        val result = client.execute(credentials(), "bookmark", mapOf("tweet_id" to "123"), "ja")

        assertTrue(result.contains("bookmark"))
        assertEquals(2, interceptor.requests.size)
        assertEquals("transaction-1", interceptor.requests[0].header(TRANSACTION_HEADER))
        assertEquals("transaction-2", interceptor.requests[1].header(TRANSACTION_HEADER))
        assertEquals(1, transactions.invalidations)
        assertEquals(
            listOf(
                "POST /i/api/graphql/mutation-id/CreateBookmark",
                "POST /i/api/graphql/mutation-id/CreateBookmark",
            ),
            transactions.generated,
        )
        assertTrue(interceptor.requests[0].bodyText().contains("\"queryId\":\"mutation-id\""))
        assertFalse(interceptor.requests[0].bodyText().contains("fieldToggles"))
    }

    @Test
    fun postQueryCarriesTheHeaderUsingItsActualMethodAndPath() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"data":{"search":{}}}"""))
        val transactions = RecordingTransactionIdService()
        val client = client(
            profile(
                operations = mapOf(
                    "search" to operation("search-id", "SearchTimeline"),
                ),
            ),
            interceptor,
            transactions,
        )

        client.execute(credentials(), "search", mapOf("rawQuery" to "NyTD"), "ja")

        val request = interceptor.singleRequest()
        assertEquals("POST", request.method)
        assertEquals("transaction-1", request.header(TRANSACTION_HEADER))
        assertEquals(listOf("POST /i/api/graphql/search-id/SearchTimeline"), transactions.generated)
    }

    @Test
    fun transactionGenerationFailureIsRetriedWithoutSendingAnApiRequest() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"data":{"ok":true}}"""))
        val transactions = RecordingTransactionIdService(failuresBeforeSuccess = 2)
        val client = client(profile(), interceptor, transactions)

        val error = assertThrows(XApiException::class.java) {
            client.execute(credentials(), "homeForYou", mapOf("count" to 20), "ja")
        }

        assertEquals(2, transactions.generated.size)
        assertEquals(1, transactions.invalidations)
        assertTrue(interceptor.requests.isEmpty())
        assertTrue(requireNotNull(error.message).contains("Web署名を生成できません"))
        assertFalse(requireNotNull(error.message).contains("fixture transaction failure"))
    }

    @Test
    fun oneTransactionGenerationFailureRecoversBeforeTheOnlyApiRequest() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"data":{"ok":true}}"""))
        val transactions = RecordingTransactionIdService(failuresBeforeSuccess = 1)
        val client = client(profile(), interceptor, transactions)

        client.execute(credentials(), "homeForYou", mapOf("count" to 20), "ja")

        assertEquals(2, transactions.generated.size)
        assertEquals(1, transactions.invalidations)
        assertEquals(1, interceptor.requests.size)
        assertEquals("transaction-2", interceptor.singleRequest().header(TRANSACTION_HEADER))
    }

    @Test
    fun customNonOfficialProfilesKeepTheExistingUnsignedRequestBehavior() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"data":{"ok":true}}"""))
        val transactions = RecordingTransactionIdService()
        val client = client(
            profile(graphqlBaseUrl = "https://example.invalid/graphql"),
            interceptor,
            transactions,
        )

        client.execute(credentials(), "homeForYou", mapOf("count" to 20), "ja")

        val request = interceptor.singleRequest()
        assertNull(request.header(TRANSACTION_HEADER))
        assertTrue(transactions.generated.isEmpty())
    }

    @Test
    fun responseWithOnlyGraphQlErrorsIsRejected() {
        val interceptor = SequenceInterceptor(ResponseSpec(200, """{"errors":[{"message":"failed"}]}"""))
        val client = client(profile(), interceptor, RecordingTransactionIdService())

        assertThrows(XApiException::class.java) {
            client.execute(credentials(), "homeForYou", mapOf("count" to 20), "ja")
        }
    }

    private fun client(
        profile: XApiProfile,
        interceptor: SequenceInterceptor,
        transactions: RecordingTransactionIdService,
    ): AuthenticatedGraphQlClient = AuthenticatedGraphQlClient(
        client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        profile = profile,
        userAgent = "NyTD-Test-UA",
        transactionIdService = transactions,
    )

    private fun credentials() = XSessionCredentials("bearer-fixture", "auth-fixture", "csrf-fixture")

    private fun profile(
        graphqlBaseUrl: String = "https://x.com/i/api/graphql",
        operations: Map<String, XApiProfile.GraphQlOperation> = mapOf(
            "homeForYou" to operation("operation-id", "HomeTimeline"),
        ),
    ) = XApiProfile(
        graphqlBaseUrl = graphqlBaseUrl,
        featureKeys = listOf("feature-a"),
        featureDefaults = mapOf("feature-a" to true),
        operations = operations,
    )

    private fun operation(
        id: String,
        name: String,
        type: XApiProfile.OperationType = XApiProfile.OperationType.QUERY,
    ) = XApiProfile.GraphQlOperation(
        operationId = id,
        operationName = name,
        type = type,
        featureKeys = emptyList(),
        fieldToggles = emptyList(),
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
                .build()
        }

        fun singleRequest(): Request = require(requests.size == 1) { "expected one request" }.let { requests.single() }
    }

    private data class ResponseSpec(
        val statusCode: Int,
        val body: String,
    )

    private fun Request.bodyText(): String {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        const val TRANSACTION_HEADER = "X-Client-Transaction-Id"
    }
}
