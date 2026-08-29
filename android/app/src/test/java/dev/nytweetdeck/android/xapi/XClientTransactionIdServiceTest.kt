package dev.nytweetdeck.android.xapi

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Random
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XClientTransactionIdServiceTest {
    @Test
    fun generatesADesktopCompatibleTransactionIdFromOfficialFixtures() {
        val requests = mutableListOf<AssetRequest>()
        val service = fixtureService(
            clock = Clock.fixed(FIXTURE_INSTANT, ZoneOffset.UTC),
            random = FixedRandom(FIXTURE_RANDOM_BYTE),
            requests = requests,
        )
        val requestUrl = "https://x.com/i/api/graphql/id/CreateRetweet?variables=%7B%7D".toHttpUrl()

        val transactionId = service.generate("post", requestUrl)

        assertEquals(FIXTURE_TRANSACTION_ID, transactionId)
        assertEquals(
            listOf(
                AssetRequest(HOME_URL, "text/html,application/xhtml+xml"),
                AssetRequest(ON_DEMAND_URL, "*/*"),
            ),
            requests,
        )
        assertEquals(transactionId, service.generate("POST", requestUrl))
        assertEquals(2, requests.size)
    }

    @Test
    fun cachesForThirtyMinutesAndRefreshesAfterInvalidation() {
        val clock = MutableClock(FIXTURE_INSTANT)
        val requests = mutableListOf<AssetRequest>()
        val service = fixtureService(clock, FixedRandom(FIXTURE_RANDOM_BYTE), requests)
        val requestUrl = "https://x.com/i/api/graphql/id/CreateRetweet".toHttpUrl()

        service.generate("POST", requestUrl)
        clock.advance(Duration.ofMinutes(29).plusSeconds(59))
        service.generate("POST", requestUrl)
        assertEquals(2, requests.size)

        clock.advance(Duration.ofSeconds(1))
        service.generate("POST", requestUrl)
        assertEquals(4, requests.size)

        service.invalidate()
        service.generate("POST", requestUrl)
        assertEquals(6, requests.size)
    }

    @Test
    fun parsesTheVerificationKeyIndicesAndCubicAnimation() {
        val material = XClientTransactionIdService.parseSigningMaterial(
            homeHtml(),
            onDemandJavascript(),
        )

        assertTrue(material.keyBytes().contentEquals(fixtureKeyBytes()))
        assertEquals(FIXTURE_ANIMATION_KEY, material.animationKey)
    }

    @Test
    fun matchesTheDesktopEncodingVector() {
        val encoded = XClientTransactionIdService.encode(
            method = "POST",
            path = "/i/api/graphql/id/CreateRetweet",
            keyBytes = Base64.getDecoder().decode("AQIDBAUGBwg="),
            animationKey = "abcdef",
            timeNow = 123_456_789L,
            randomByte = 42,
        )

        assertEquals("KisoKS4vLC0iP+dxLVBSfQNSOMLgRgeCHetFQtUp", encoded)
    }

    @Test
    fun signsTheBundledOfficialRestHostWithoutRelaxingAssetOrigins() {
        val requests = mutableListOf<AssetRequest>()
        val service = fixtureService(
            clock = Clock.fixed(FIXTURE_INSTANT, ZoneOffset.UTC),
            random = FixedRandom(FIXTURE_RANDOM_BYTE),
            requests = requests,
        )

        val transactionId = service.generate(
            "GET",
            "https://api.twitter.com/1.1/users/show.json?screen_name=alice".toHttpUrl(),
        )

        assertTrue(transactionId.isNotBlank())
        assertEquals(listOf(HOME_URL, ON_DEMAND_URL), requests.map(AssetRequest::url))
    }

    @Test
    fun rejectsUnsafeUrlsMalformedAssetsAndOversizedBodies() {
        val fetcher = XClientTransactionIdService.AssetFetcher { _, _ -> "" }
        assertThrows(IllegalArgumentException::class.java) {
            XClientTransactionIdService(
                fetcher = fetcher,
                homeUrl = "http://x.com/home".toHttpUrl(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            XClientTransactionIdService(
                fetcher = fetcher,
                assetBaseUrl = "https://x.com/responsive-web/client-web/".toHttpUrl(),
            )
        }

        val malformed = XClientTransactionIdService(
            fetcher = XClientTransactionIdService.AssetFetcher { _, _ -> "<html></html>" },
            clock = Clock.fixed(FIXTURE_INSTANT, ZoneOffset.UTC),
            random = FixedRandom(FIXTURE_RANDOM_BYTE),
        )
        val malformedError = assertThrows(XApiException::class.java) {
            malformed.generate("POST", "https://x.com/i/api/graphql/id/CreateRetweet".toHttpUrl())
        }
        assertEquals(502, malformedError.statusCode)

        val oversized = XClientTransactionIdService(
            fetcher = XClientTransactionIdService.AssetFetcher { _, _ ->
                "x".repeat(XClientTransactionIdService.MAX_ASSET_BYTES + 1)
            },
            clock = Clock.fixed(FIXTURE_INSTANT, ZoneOffset.UTC),
            random = FixedRandom(FIXTURE_RANDOM_BYTE),
        )
        val oversizedError = assertThrows(XApiException::class.java) {
            oversized.generate("POST", "https://x.com/i/api/graphql/id/CreateRetweet".toHttpUrl())
        }
        assertEquals(502, oversizedError.statusCode)

        val official = fixtureService(
            Clock.fixed(FIXTURE_INSTANT, ZoneOffset.UTC),
            FixedRandom(FIXTURE_RANDOM_BYTE),
            mutableListOf(),
        )
        val unsafeError = assertThrows(XApiException::class.java) {
            official.generate("POST", "https://abs.twimg.com/i/api/graphql/id/CreateRetweet".toHttpUrl())
        }
        assertEquals(502, unsafeError.statusCode)
    }

    private fun fixtureService(
        clock: Clock,
        random: Random,
        requests: MutableList<AssetRequest>,
    ): XClientTransactionIdService {
        val assets = mapOf(
            HOME_URL to homeHtml(),
            ON_DEMAND_URL to onDemandJavascript(),
        )
        return XClientTransactionIdService(
            fetcher = XClientTransactionIdService.AssetFetcher { url, accept ->
                requests += AssetRequest(url.toString(), accept)
                assets[url.toString()] ?: throw XApiException("fixture asset is unavailable", 404)
            },
            clock = clock,
            random = random,
        )
    }

    private fun homeHtml(): String = buildString {
        append("59924:\"ondemand.s\",59924:\"$ON_DEMAND_HASH\"")
        append("<meta name=\"twitter-site-verification\" content=\"")
        append(Base64.getEncoder().encodeToString(fixtureKeyBytes()))
        append("\">")
        repeat(4) { index ->
            append("<svg id=\"loading-x-anim-$index\"><g><path d=\"M0\"></path>")
            append("<path d=\"M 10,30 ")
            repeat(16) {
                append("C 10,20 30,40 50,60 70,80 90,100 110 ")
            }
            append("\"></path></g></svg>")
        }
    }

    private fun onDemandJavascript(): String = "(a[2], 16)(b[15], 16)"

    private fun fixtureKeyBytes(): ByteArray = ByteArray(24) { index -> (index + 1).toByte() }.also {
        it[5] = 4
    }

    private data class AssetRequest(
        val url: String,
        val accept: String,
    )

    private class FixedRandom(
        private val value: Int,
    ) : Random() {
        override fun nextInt(bound: Int): Int {
            check(bound == 256)
            return value
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = if (zone == ZoneOffset.UTC) this else Clock.fixed(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        const val HOME_URL = "https://x.com/home"
        const val ON_DEMAND_HASH = "e89b799f9742fd4e"
        const val ON_DEMAND_URL =
            "https://abs.twimg.com/responsive-web/client-web/ondemand.s.${ON_DEMAND_HASH}a.js"
        const val FIXTURE_ANIMATION_KEY = "a141e100100"
        const val FIXTURE_RANDOM_BYTE = 42
        const val FIXTURE_TRANSACTION_ID =
            "KisoKS4vLi0iIyAhJickJTo7ODk+Pzw9Mj/ncS2CHMyrbA2byoEgljnfE5aJKQ"
        val FIXTURE_INSTANT: Instant = Instant.ofEpochSecond(1_682_924_400L + 123_456_789L)
    }
}
