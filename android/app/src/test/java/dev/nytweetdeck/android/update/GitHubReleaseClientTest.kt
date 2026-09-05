package dev.nytweetdeck.android.update

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseClientTest {
    @Test
    fun selectsTheNewestStableAndroidReleaseAndItsExactApkAsset() {
        val interceptor = JsonInterceptor(
            """[
              {"tag_name":"v1.5.0","published_at":"2026-09-05T00:00:00Z","draft":false,"prerelease":false,"assets":[
                {"name":"NyTweetDeck-1.5.0.zip","state":"uploaded","browser_download_url":"https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.5.0/NyTweetDeck.zip","size":1}
              ]},
              {"tag_name":"android-v0.3.0-beta.1","published_at":"2026-09-04T00:00:00Z","draft":false,"prerelease":true,"assets":[
                {"name":"NyTweetDeck-Android-v0.3.0-beta.1.apk","state":"uploaded","browser_download_url":"https://github.com/roflsunriz/NyTweetDeck/releases/download/android-v0.3.0-beta.1/NyTweetDeck-Android-v0.3.0-beta.1.apk","size":2}
              ]},
              {"tag_name":"android-v0.2.2","published_at":"2026-09-03T00:00:00Z","draft":false,"prerelease":false,"assets":[
                {"name":"NyTweetDeck-Android-v0.2.2.apk.sha256","state":"uploaded","browser_download_url":"https://github.com/roflsunriz/NyTweetDeck/releases/download/android-v0.2.2/NyTweetDeck-Android-v0.2.2.apk.sha256","size":64},
                {"name":"NyTweetDeck-Android-v0.2.2.apk","state":"uploaded","browser_download_url":"https://github.com/roflsunriz/NyTweetDeck/releases/download/android-v0.2.2/NyTweetDeck-Android-v0.2.2.apk","size":123456}
              ]}
            ]""".trimIndent(),
        )

        val apk = client(interceptor).latestStableAndroidApk()

        assertEquals("android-v0.2.2", apk.tagName)
        assertEquals("NyTweetDeck-Android-v0.2.2.apk", apk.assetName)
        assertEquals(123456L, apk.sizeBytes)
        assertEquals("application/vnd.github+json", interceptor.accept)
        assertEquals("2022-11-28", interceptor.apiVersion)
        assertEquals("NyTweetDeck-Android", interceptor.userAgent)
    }

    @Test
    fun rejectsAnApkAssetHostedOutsideGitHub() {
        val interceptor = JsonInterceptor(
            """[{"tag_name":"android-v0.2.2","draft":false,"prerelease":false,"assets":[
              {"name":"NyTweetDeck-Android-v0.2.2.apk","state":"uploaded","browser_download_url":"https://example.invalid/update.apk","size":123}
            ],"published_at":"2026-09-03T00:00:00Z"}]""",
        )

        assertThrows(IOException::class.java) {
            client(interceptor).latestStableAndroidApk()
        }
    }

    @Test
    fun reportsGitHubApiFailuresWithoutReturningAnAsset() {
        val interceptor = JsonInterceptor("{}", statusCode = 403)

        assertThrows(IOException::class.java) {
            client(interceptor).latestStableAndroidApk()
        }
    }

    @Test
    fun choosesHighestVersionEvenIfAnOlderReleaseWasPublishedLater() {
        val releases = listOf("0.2.10" to "2026-09-01", "0.2.9" to "2026-09-05").joinToString(",") { (version, day) ->
            """{"tag_name":"android-v$version","published_at":"${day}T00:00:00Z","draft":false,"prerelease":false,"assets":[
              {"name":"NyTweetDeck-Android-v$version.apk","state":"uploaded","browser_download_url":"https://github.com/roflsunriz/NyTweetDeck/releases/download/android-v$version/NyTweetDeck-Android-v$version.apk"}
            ]}"""
        }
        assertEquals("android-v0.2.10", client(JsonInterceptor("[$releases]")).latestStableAndroidApk().tagName)
    }

    private fun client(interceptor: JsonInterceptor) = GitHubReleaseClient(
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
    )

    private class JsonInterceptor(
        private val body: String,
        private val statusCode: Int = 200,
    ) : Interceptor {
        var accept: String? = null
        var apiVersion: String? = null
        var userAgent: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            accept = request.header("Accept")
            apiVersion = request.header("X-GitHub-Api-Version")
            userAgent = request.header("User-Agent")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message(if (statusCode == 200) "OK" else "Forbidden")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
