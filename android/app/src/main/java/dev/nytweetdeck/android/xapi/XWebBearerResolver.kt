package dev.nytweetdeck.android.xapi

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

class XWebBearerResolver(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val homeUrl: HttpUrl = X_HOME,
    private val assetBaseUrl: HttpUrl = ASSET_BASE,
) {
    @Volatile
    private var cachedToken: String? = null

    fun require(): String = cachedToken ?: synchronized(this) {
        cachedToken ?: resolve().also { cachedToken = it }
    }

    @Synchronized
    fun invalidate() {
        cachedToken = null
    }

    private fun resolve(): String {
        val home = get(homeUrl, "X公式ログインページ")
        val entryAsset = extractEntryAsset(home)
            ?: throw XApiException("X公式ログイン資産を検出できません。", 502)
        val entry = get(assetBaseUrl.resolve(entryAsset) ?: invalidAsset(), "X公式ログイン資産")
        val guestAsset = extractGuestAsset(entry)
            ?: throw XApiException("X公式Guest認証資産を検出できません。", 502)
        val guest = get(assetBaseUrl.resolve(guestAsset) ?: invalidAsset(), "X公式Guest認証資産")
        val encoded = extractBearer(guest)
            ?: throw XApiException("X公式Web Bearerを検出できません。", 502)
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }

    private fun get(url: HttpUrl, source: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw XApiException("${source}の取得に失敗しました。HTTP ${response.code}", response.code)
                }
                return response.body.string()
            }
        } catch (exception: XApiException) {
            throw exception
        } catch (exception: Exception) {
            throw XApiException("${source}の通信に失敗しました。", cause = exception)
        }
    }

    private fun invalidAsset(): Nothing = throw XApiException("X公式Web資産URLが不正です。", 502)

    internal companion object {
        private val X_HOME = "https://x.com/".toHttpUrl()
        private val ASSET_BASE = "https://abs.twimg.com/x-web/x-web/".toHttpUrl()
        private val ENTRY_ASSET = Regex(
            "https://abs\\.twimg\\.com/x-web/x-web/(entry-client-logged-out-[A-Za-z0-9_-]+\\.js)",
        )
        private val GUEST_ASSET = Regex("assets/guest-token-[A-Za-z0-9_-]+\\.js")
        private val BEARER = Regex("Bearer (AAAAA[^`\\\"']+)")

        fun extractEntryAsset(document: String): String? =
            ENTRY_ASSET.find(document)?.groupValues?.get(1)

        fun extractGuestAsset(document: String): String? = GUEST_ASSET.find(document)?.value

        fun extractBearer(document: String): String? = BEARER.find(document)?.groupValues?.get(1)
    }
}
