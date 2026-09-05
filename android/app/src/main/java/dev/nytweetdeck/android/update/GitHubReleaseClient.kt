package dev.nytweetdeck.android.update

import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl
import java.io.IOException
import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class LatestAndroidApk(
    val tagName: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
)

internal class GitHubReleaseClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val releasesUrl: HttpUrl = RELEASES_URL.toHttpUrl(),
) {
    fun latestStableAndroidApk(): LatestAndroidApk {
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NyTweetDeck-Android")
            .get()
            .build()
        val releases = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub Releases API returned HTTP ${response.code}.")
            }
            val body = response.body
            val source = body.source()
            source.request(MAX_RESPONSE_BYTES + 1L)
            if (source.buffer.size > MAX_RESPONSE_BYTES) {
                throw IOException("GitHub Releases API response is too large.")
            }
            runCatching { Json.parseToJsonElement(source.readUtf8()).jsonArray }
                .getOrElse { throw IOException("GitHub Releases API response is invalid.", it) }
        }
        return releases.mapNotNull(::releaseApk).maxWithOrNull { left, right ->
            when {
                left.apk.isNewerThan(right.apk.tagName.removePrefix("android-v")) -> 1
                right.apk.isNewerThan(left.apk.tagName.removePrefix("android-v")) -> -1
                else -> left.publishedAt.compareTo(right.publishedAt)
            }
        }?.apk
            ?: throw IOException("A stable Android APK release was not found.")
    }

    private fun releaseApk(element: kotlinx.serialization.json.JsonElement): PublishedApk? {
        val release = element as? JsonObject ?: return null
        if (release.boolean("draft") || release.boolean("prerelease")) return null
        val tagName = release.string("tag_name") ?: return null
        val publishedAt = release.string("published_at")
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        val version = ANDROID_TAG.matchEntire(tagName)?.groupValues?.get(1) ?: return null
        val expectedAssetName = "NyTweetDeck-Android-v$version.apk"
        val assets = release["assets"] as? JsonArray ?: return null
        val asset = assets
            .mapNotNull { it as? JsonObject }
            .firstOrNull { candidate ->
                candidate.string("name") == expectedAssetName &&
                    candidate.string("state") == "uploaded"
            }
            ?: return null
        val downloadUrl = verifiedDownloadUrl(
            asset.string("browser_download_url") ?: return null,
            tagName,
            expectedAssetName,
        ) ?: return null
        return PublishedApk(
            apk = LatestAndroidApk(
                tagName = tagName,
                assetName = expectedAssetName,
                downloadUrl = downloadUrl,
                sizeBytes = (asset["size"] as? JsonPrimitive)?.longOrNull,
            ),
            publishedAt = publishedAt,
        )
    }

    private fun JsonObject.boolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun verifiedDownloadUrl(value: String, tagName: String, assetName: String): String? {
        val verified = verifiedExternalHttpsUrl(value, setOf("github.com")) ?: return null
        val uri = runCatching { URI(verified) }.getOrNull() ?: return null
        val expectedPath = "/roflsunriz/NyTweetDeck/releases/download/$tagName/$assetName"
        return verified.takeIf {
            uri.rawPath == expectedPath && uri.rawQuery == null && uri.rawFragment == null
        }
    }

    private data class PublishedApk(
        val apk: LatestAndroidApk,
        val publishedAt: Instant,
    )

    private companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/roflsunriz/NyTweetDeck/releases?per_page=100"
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        val ANDROID_TAG = Regex("android-v([0-9]+\\.[0-9]+\\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?)")
    }
}
