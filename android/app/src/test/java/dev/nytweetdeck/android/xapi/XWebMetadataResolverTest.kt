package dev.nytweetdeck.android.xapi

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XWebMetadataResolverTest {
    @Test
    fun resolvesOfficialAssetFixturesAndAppliesTheCompleteSnapshot() {
        val profile = currentProfile()
        val home = profile.requireOperation("homeForYou")
        val createPost = profile.requireOperation("createPost")
        val fixture = fixtureResolver(profile, includeCreatePostChunk = true)

        val metadata = fixture.resolver.resolve(
            linkedSetOf(home.operationName, createPost.operationName),
        )
        val applied = metadata.applyTo(
            profile.copy(
                operations = linkedMapOf(
                    "homeForYou" to home,
                    "createPost" to createPost,
                ),
            ),
        )

        assertEquals(MAIN_ASSET_URL.substringAfterLast('/'), metadata.sourceVersion)
        assertEquals(
            listOf(
                "responsive_web_graphql_timeline_navigation_enabled",
                "rweb_video_screen_enabled",
                "responsive_web_edit_tweet_api_enabled",
            ),
            metadata.allFeatureKeys,
        )
        assertEquals(
            mapOf(
                "responsive_web_graphql_timeline_navigation_enabled" to true,
                "rweb_video_screen_enabled" to false,
                "responsive_web_edit_tweet_api_enabled" to true,
            ),
            metadata.featureDefaults,
        )
        assertEquals(home.operationId, applied.requireOperation("homeForYou").operationId)
        assertEquals(createPost.operationId, applied.requireOperation("createPost").operationId)
        assertEquals(
            listOf("withPayments"),
            applied.requireOperation("createPost").fieldToggles,
        )
        assertEquals(
            listOf(HOME_URL, MAIN_ASSET_URL, CREATE_POST_CHUNK_URL),
            fixture.requestedUrls,
        )
    }

    @Test
    fun reportsMissingRequiredOperationInsteadOfFailingTheWholeRefresh() {
        val profile = currentProfile()
        val home = profile.requireOperation("homeForYou")
        val createPost = profile.requireOperation("createPost")
        val fixture = fixtureResolver(profile, includeCreatePostChunk = false)

        val metadata = fixture.resolver.resolve(
            linkedSetOf(home.operationName, createPost.operationName),
        )

        assertEquals(listOf(createPost.operationName), metadata.missingOperations)
        assertEquals(setOf(home.operationName), metadata.operationsByName.keys)
        val applied = metadata.applyTo(
            profile.copy(
                operations = linkedMapOf(
                    "homeForYou" to home,
                    "createPost" to createPost,
                ),
            ),
        )
        assertEquals(home.operationId, applied.requireOperation("homeForYou").operationId)
        assertEquals(createPost.operationId, applied.requireOperation("createPost").operationId)
        assertEquals(listOf(HOME_URL, MAIN_ASSET_URL), fixture.requestedUrls)
    }

    @Test
    fun fetchesHomeWithSavedWebSessionHeaders() {
        val profile = currentProfile()
        val home = profile.requireOperation("homeForYou")
        val observedHeaders = mutableMapOf<String, Map<String, String>>()
        val resolver = XWebMetadataResolver(
            XWebMetadataResolver.AssetFetcher { url, headers ->
                observedHeaders[url.toString()] = headers
                when (url.toString()) {
                    HOME_URL -> "<script src=\"$MAIN_ASSET_URL\"></script>"
                    MAIN_ASSET_URL -> operationJavascript(
                        operation = home,
                        featureKeys = listOf("responsive_web_graphql_timeline_navigation_enabled"),
                        fieldToggles = emptyList(),
                    )
                    else -> throw XApiException("fixture asset is unavailable", 404)
                }
            },
            sessionProvider = {
                XSessionCredentials(
                    bearerToken = "bearer",
                    authToken = "auth-tok",
                    csrfToken = "csrf-tok",
                )
            },
        )

        val metadata = resolver.resolve(linkedSetOf(home.operationName))

        assertEquals(home.operationId, metadata.operationsByName[home.operationName]?.operationId)
        assertEquals(
            "auth_token=auth-tok; ct0=csrf-tok",
            observedHeaders[HOME_URL]?.get("Cookie"),
        )
        assertEquals("csrf-tok", observedHeaders[HOME_URL]?.get("X-CSRF-Token"))
        assertTrue(observedHeaders[MAIN_ASSET_URL]?.get("Cookie").isNullOrEmpty())
        assertEquals(
            mapOf(
                "Cookie" to "auth_token=auth-tok; ct0=csrf-tok",
                "X-CSRF-Token" to "csrf-tok",
                "X-Twitter-Auth-Type" to "OAuth2Session",
                "X-Twitter-Active-User" to "yes",
                "Accept" to "text/html",
                "Referer" to "https://x.com/",
            ),
            resolver.homeHeaders(),
        )
    }

    @Test
    fun discoversListOperationsFromListChunks() {
        val requestedUrls = mutableListOf<String>()
        val listJavascript = """
            436870(e){e.exports={queryId:"listQueryId01",operationName:"ListLatestTweetsTimeline",
            operationType:"query",metadata:{featureSwitches:[],fieldToggles:[]}}}
        """.trimIndent()
        val resolver = XWebMetadataResolver(
            XWebMetadataResolver.AssetFetcher { url, _ ->
                requestedUrls += url.toString()
                when (url.toString()) {
                    HOME_URL -> "<script src=\"$MAIN_ASSET_URL\"></script>" +
                        "{1:\"bundle.UserLists\",1:\"$CHUNK_HASH\"}"
                    MAIN_ASSET_URL -> "no operations here"
                    "https://abs.twimg.com/responsive-web/client-web/" +
                        "bundle.UserLists.${CHUNK_HASH}a.js" -> listJavascript
                    else -> throw XApiException("fixture asset is unavailable", 404)
                }
            },
        )

        val metadata = resolver.resolve(linkedSetOf("ListLatestTweetsTimeline"))

        assertEquals(
            "listQueryId01",
            metadata.operationsByName["ListLatestTweetsTimeline"]?.operationId,
        )
        assertTrue(metadata.missingOperations.isEmpty())
    }

    @Test
    fun rejectsUnsafeUrlsMalformedOperationMetadataAndOversizedInputs() {
        val fetcher = XWebMetadataResolver.AssetFetcher { _, _ -> "" }

        assertThrows(IllegalArgumentException::class.java) {
            XWebMetadataResolver(fetcher, homeUrl = "http://x.com/home".toHttpUrl())
        }
        assertThrows(IllegalArgumentException::class.java) {
            XWebMetadataResolver(
                fetcher,
                homeUrl = HOME_URL.toHttpUrl(),
                assetBaseUrl = "https://x.com/responsive-web/client-web/".toHttpUrl(),
            )
        }

        val resolver = XWebMetadataResolver(fetcher)
        assertTrue(
            resolver.parseOperations(
                """
                queryId:"invalid/id",operationName:"HomeTimeline",operationType:"query",
                metadata:{featureSwitches:["feature_a"],fieldToggles:["withPayments"]}}
                """.trimIndent(),
            ).isEmpty(),
        )
        assertTrue(
            resolver.parseOperations(
                """
                queryId:"${currentProfile().requireOperation("homeForYou").operationId}",
                operationName:"HomeTimeline",operationType:"query",
                metadata:{featureSwitches:["feature_a"],fieldToggles:["invalid-toggle"]}}
                """.trimIndent(),
            ).isEmpty(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            resolver.parseBooleanFeatures("x".repeat(MAX_EXPECTED_ASSET_BYTES + 1))
        }
    }

    private fun fixtureResolver(
        profile: XApiProfile,
        includeCreatePostChunk: Boolean,
    ): ResolverFixture {
        val home = profile.requireOperation("homeForYou")
        val createPost = profile.requireOperation("createPost")
        val assets = linkedMapOf<String, String>(
            HOME_URL to homeHtml(includeCreatePostChunk),
            MAIN_ASSET_URL to operationJavascript(
                operation = home,
                featureKeys = listOf(
                    "responsive_web_graphql_timeline_navigation_enabled",
                    "rweb_video_screen_enabled",
                ),
                fieldToggles = listOf("withArticlePlainText"),
            ),
        )
        if (includeCreatePostChunk) {
            assets[CREATE_POST_CHUNK_URL] = operationJavascript(
                operation = createPost,
                featureKeys = listOf(
                    "responsive_web_graphql_timeline_navigation_enabled",
                    "responsive_web_edit_tweet_api_enabled",
                ),
                fieldToggles = listOf("withPayments"),
            )
        }
        val requestedUrls = mutableListOf<String>()
        val resolver = XWebMetadataResolver(
            XWebMetadataResolver.AssetFetcher { url, _ ->
                requestedUrls += url.toString()
                assets[url.toString()]
                    ?: throw XApiException("fixture asset is unavailable", 404)
            },
        )
        return ResolverFixture(resolver, requestedUrls)
    }

    private fun homeHtml(includeCreatePostChunk: Boolean): String = buildString {
        appendLine("<script src=\"$MAIN_ASSET_URL\"></script>")
        if (includeCreatePostChunk) {
            append("{17:\"bundle.CreateTweet\",17:\"$CHUNK_HASH\"}")
        }
        append(
            "\"responsive_web_graphql_timeline_navigation_enabled\":{\"value\":true}," +
                "\"rweb_video_screen_enabled\":{\"value\":false}," +
                "\"responsive_web_edit_tweet_api_enabled\":{\"value\":true}",
        )
    }

    private fun operationJavascript(
        operation: XApiProfile.GraphQlOperation,
        featureKeys: List<String>,
        fieldToggles: List<String>,
    ): String = """
        436870(e){e.exports={queryId:"${operation.operationId}",operationName:"${operation.operationName}",
        operationType:"${operation.type.name.lowercase()}",metadata:{featureSwitches:${javascriptList(featureKeys)},
        fieldToggles:${javascriptList(fieldToggles)}}}}
    """.trimIndent()

    private fun javascriptList(values: List<String>): String = values.joinToString(
        prefix = "[",
        postfix = "]",
        separator = ",",
    ) { value -> "\"$value\"" }

    private fun currentProfile(): XApiProfile = XApiProfile.parse(
        resource("web-current.json"),
        resource("web-boolean-feature-defaults.json"),
    )

    private fun resource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource(name),
    ).readText()

    private data class ResolverFixture(
        val resolver: XWebMetadataResolver,
        val requestedUrls: List<String>,
    )

    private companion object {
        const val MAX_EXPECTED_ASSET_BYTES = 8 * 1024 * 1024
        const val HOME_URL = "https://x.com/home"
        const val CHUNK_HASH = "8f2a1b3c4d5e6f70"
        const val MAIN_ASSET_URL =
            "https://abs.twimg.com/responsive-web/client-web/main.8f2a1b3c4d5e6f70.js"
        const val CREATE_POST_CHUNK_URL =
            "https://abs.twimg.com/responsive-web/client-web/bundle.CreateTweet.8f2a1b3c4d5e6f70a.js"
    }
}
