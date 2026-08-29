package dev.nytweetdeck.android.xapi

import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.Random
import java.util.regex.Pattern
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Generates X Web's dynamic `X-Client-Transaction-Id` header from its current public assets.
 *
 * The signing material is never fixed in the application: the official X Web page and its
 * corresponding on-demand chunk are fetched afresh every 30 minutes, or after [invalidate].
 */
open class XClientTransactionIdService(
    private val fetcher: AssetFetcher,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = SecureRandom(),
    private val cacheDuration: Duration = DEFAULT_CACHE_DURATION,
    private val homeUrl: HttpUrl = WEB_HOME,
    private val assetBaseUrl: HttpUrl = ASSET_BASE,
) {
    @Volatile
    private var cachedMaterial: SigningMaterial? = null

    @Volatile
    private var cachedAt: Instant? = null

    init {
        require(!cacheDuration.isNegative) { "X Web署名キャッシュ期間が不正です。" }
        requireOfficialUrl(homeUrl, X_WEB_HOST)
        require(homeUrl.encodedPath == HOME_PATH && homeUrl.query == null && homeUrl.fragment == null) {
            "X公式WebホームURLが不正です。"
        }
        requireOfficialUrl(assetBaseUrl, ASSET_HOST)
        require(assetBaseUrl.encodedPath == ASSET_BASE_PATH &&
            assetBaseUrl.query == null &&
            assetBaseUrl.fragment == null
        ) {
            "X公式Web資産ベースURLが不正です。"
        }
    }

    constructor(
        client: OkHttpClient,
        userAgent: String,
        clock: Clock = Clock.systemUTC(),
        random: Random = SecureRandom(),
        cacheDuration: Duration = DEFAULT_CACHE_DURATION,
        homeUrl: HttpUrl = WEB_HOME,
        assetBaseUrl: HttpUrl = ASSET_BASE,
    ) : this(
        fetcher = OkHttpAssetFetcher(client, userAgent),
        clock = clock,
        random = random,
        cacheDuration = cacheDuration,
        homeUrl = homeUrl,
        assetBaseUrl = assetBaseUrl,
    )

    /** Creates a transaction ID for an official X Web or authenticated REST API request. */
    open fun generate(method: String, requestUrl: HttpUrl): String {
        val normalizedMethod = method.uppercase(Locale.ROOT)
        require(METHOD.matches(normalizedMethod)) { "HTTP methodの形式が不正です。" }
        validateRequestUrl(requestUrl)

        val material = signingMaterial()
        val timeNow = clock.instant().epochSecond - X_TIME_EPOCH_SECONDS
        return encode(
            method = normalizedMethod,
            path = requestUrl.encodedPath,
            keyBytes = material.keyBytes(),
            animationKey = material.animationKey,
            timeNow = timeNow,
            randomByte = random.nextInt(RANDOM_BYTE_BOUND),
        )
    }

    @Synchronized
    open fun invalidate() {
        cachedMaterial = null
        cachedAt = null
    }

    private fun signingMaterial(): SigningMaterial {
        cachedMaterial?.let { material ->
            cachedAt?.let { loadedAt ->
                if (loadedAt.plus(cacheDuration).isAfter(clock.instant())) {
                    return material
                }
            }
        }
        return synchronized(this) {
            cachedMaterial?.let { material ->
                cachedAt?.let { loadedAt ->
                    if (loadedAt.plus(cacheDuration).isAfter(clock.instant())) {
                        return@synchronized material
                    }
                }
            }

            loadSigningMaterial().also { material ->
                cachedMaterial = material
                cachedAt = clock.instant()
            }
        }
    }

    private fun loadSigningMaterial(): SigningMaterial {
        val homeHtml = fetchText(homeUrl, HTML_ACCEPT, "X公式Webページ")
        val onDemandUrl = resolveOnDemandUrl(homeHtml)
        val onDemandSource = fetchText(onDemandUrl, JAVASCRIPT_ACCEPT, "X公式Web署名資産")
        return parseSigningMaterial(homeHtml, onDemandSource)
    }

    private fun fetchText(url: HttpUrl, accept: String, source: String): String {
        validateFetchedUrl(url)
        val body = try {
            fetcher.fetch(url, accept)
        } catch (exception: XApiException) {
            throw exception
        } catch (exception: Exception) {
            throw XApiException("${source}の通信に失敗しました。", cause = exception)
        }
        requireInputSize(body, source)
        return body
    }

    private fun resolveOnDemandUrl(homeHtml: String): HttpUrl {
        requireInputSize(homeHtml, "X公式Webページ")
        val chunkMatch = ON_DEMAND_CHUNK.find(homeHtml)
            ?: throw XApiException("X Web署名チャンクIDを解決できませんでした。", 502)
        val chunkId = chunkMatch.groupValues[1]
        val hashPattern = Regex(
            """\b${Regex.escape(chunkId)}:\s*([\"'])([A-Za-z0-9_-]{8,200})\1""",
        )
        val hash = hashPattern.findAll(homeHtml).lastOrNull()?.groupValues?.get(2)
            ?: throw XApiException("X Web署名チャンクを解決できませんでした。", 502)
        return resolveAssetUrl("ondemand.s.${hash}a.js")
    }

    private fun resolveAssetUrl(relativePath: String): HttpUrl {
        val url = assetBaseUrl.resolve(relativePath)
            ?: throw XApiException("X公式Web資産URLが不正です。", 502)
        try {
            requireOfficialUrl(url, ASSET_HOST)
        } catch (exception: IllegalArgumentException) {
            throw XApiException("X公式Web資産URLが不正です。", 502, exception)
        }
        if (!url.encodedPath.startsWith(ASSET_BASE_PATH) || url.query != null || url.fragment != null) {
            throw XApiException("X公式Web資産URLが不正です。", 502)
        }
        return url
    }

    private fun validateFetchedUrl(url: HttpUrl) {
        try {
            requireOfficialUrl(url)
        } catch (exception: IllegalArgumentException) {
            throw XApiException("X公式Web資産URLが不正です。", 502, exception)
        }
        if (url.host.lowercase(Locale.ROOT) == ASSET_HOST &&
            !url.encodedPath.startsWith(ASSET_BASE_PATH)
        ) {
            throw XApiException("X公式Web資産URLが不正です。", 502)
        }
    }

    private fun validateRequestUrl(url: HttpUrl) {
        if (!supportsOfficialApiRequest(url)) {
            throw XApiException("X公式Web API URLが不正です。", 502)
        }
        if (url.encodedPath.isEmpty() || url.encodedPath.length > MAX_REQUEST_PATH_LENGTH) {
            throw XApiException("X公式Web API pathが不正です。", 502)
        }
    }

    /** Fetches a validated public X Web asset. No caller-controlled URL is passed unchecked. */
    fun interface AssetFetcher {
        fun fetch(url: HttpUrl, accept: String): String
    }

    internal class SigningMaterial(
        keyBytes: ByteArray,
        val animationKey: String,
    ) {
        private val value = keyBytes.copyOf()

        fun keyBytes(): ByteArray = value.copyOf()
    }

    private class OkHttpAssetFetcher(
        client: OkHttpClient,
        userAgent: String,
    ) : AssetFetcher {
        private val client = client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        private val userAgent = userAgent.trim().also {
            require(it.isNotEmpty()) { "X Web User-Agentが空です。" }
            require(!it.contains('\r') && !it.contains('\n')) { "X Web User-Agentが不正です。" }
        }

        override fun fetch(url: HttpUrl, accept: String): String {
            val request = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("Accept-Language", "ja")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", userAgent)
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw XApiException(
                            "X公式Web資産の取得に失敗しました。HTTP ${response.code}",
                            response.code,
                        )
                    }
                    if (response.body.contentLength() > MAX_ASSET_BYTES) {
                        throw XApiException("X公式Web資産が上限サイズを超えています。", 502)
                    }
                    return response.body.string()
                }
            } catch (exception: XApiException) {
                throw exception
            } catch (exception: IOException) {
                throw XApiException("X公式Web資産の通信に失敗しました。", cause = exception)
            }
        }
    }

    companion object {
        internal const val MAX_ASSET_BYTES = 8 * 1024 * 1024
        private const val X_WEB_HOST = "x.com"
        private const val API_TWITTER_HOST = "api.twitter.com"
        private const val ASSET_HOST = "abs.twimg.com"
        private const val HOME_PATH = "/home"
        private const val ASSET_BASE_PATH = "/responsive-web/client-web/"
        private const val X_TIME_EPOCH_SECONDS = 1_682_924_400L
        private const val DEFAULT_KEYWORD = "obfiowerehiring"
        private const val ADDITIONAL_RANDOM_NUMBER = 3
        private const val FRAME_VARIANT_COUNT = 4
        private const val FRAME_ROW_COUNT = 16
        private const val RANDOM_BYTE_BOUND = 256
        private const val MAX_REQUEST_PATH_LENGTH = 4_096
        private const val MIN_KEY_BYTES = 6
        private const val MAX_KEY_BYTES = 1_024
        private const val MAX_INDEX_COUNT = 64
        private const val MAX_ANIMATION_FRAMES = 64
        private const val MAX_ANIMATION_ROWS = 128
        private const val MAX_ANIMATION_ROW_VALUES = 256
        private const val MIN_ANIMATION_ROW_VALUES = 11

        private val WEB_HOME = "https://x.com/home".toHttpUrl()
        private val ASSET_BASE = "https://abs.twimg.com$ASSET_BASE_PATH".toHttpUrl()
        private val DEFAULT_CACHE_DURATION: Duration = Duration.ofMinutes(30)
        private val OFFICIAL_API_HOSTS = setOf(X_WEB_HOST, API_TWITTER_HOST)
        private val METHOD = Regex("[A-Z]{1,32}")
        private val META_TAG = Regex(
            """<meta\b[^>]*\bname\s*=\s*([\"'])twitter-site-verification\1[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val CONTENT_ATTRIBUTE = Regex(
            """\bcontent\s*=\s*([\"'])(.*?)\1""",
            RegexOption.IGNORE_CASE,
        )
        private val ON_DEMAND_CHUNK = Regex(
            """(\d{1,10}):\s*([\"'])ondemand\.s\2""",
            RegexOption.IGNORE_CASE,
        )
        private val INDEX = Regex("""\(\w\[(\d{1,3})],\s*16\)""")
        private val SVG = Regex(
            """<svg\b[^>]*\bid\s*=\s*([\"'])loading-x-anim-[^\"']+\1[^>]*>(.*?)</svg>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val PATH = Regex(
            """<path\b[^>]*\bd\s*=\s*([\"'])(.*?)\1[^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val NON_DIGITS = Regex("[^\\d]+")
        private val CUBIC_SPLITTER: Pattern = Pattern.compile("C")
        private val ANIMATION_KEY = Regex("[0-9a-f]{1,256}")
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml"
        private const val JAVASCRIPT_ACCEPT = "*/*"

        internal fun parseSigningMaterial(homeHtml: String, onDemandSource: String): SigningMaterial {
            requireInputSize(homeHtml, "X公式Webページ")
            requireInputSize(onDemandSource, "X公式Web署名資産")
            val metaTag = META_TAG.find(homeHtml)
                ?: throw XApiException("X Web署名キーが見つかりません。", 502)
            val content = CONTENT_ATTRIBUTE.find(metaTag.value)?.groupValues?.get(2)
                ?.takeIf(String::isNotBlank)
                ?: throw XApiException("X Web署名キーが空です。", 502)
            val keyBytes = try {
                Base64.getDecoder().decode(content)
            } catch (exception: IllegalArgumentException) {
                throw XApiException("X Web署名キーを解析できません。", cause = exception)
            }
            if (keyBytes.size !in MIN_KEY_BYTES..MAX_KEY_BYTES) {
                throw XApiException("X Web署名キーのサイズが不正です。", 502)
            }

            val indices = INDEX.findAll(onDemandSource).mapTo(ArrayList()) { match ->
                match.groupValues[1].toIntOrNull()
                    ?: throw XApiException("X Web署名インデックスを解析できません。", 502)
            }
            if (indices.size !in 2..MAX_INDEX_COUNT) {
                throw XApiException("X Web署名インデックスを解析できません。", 502)
            }
            val rowIndexKey = indices.first()
            val frameTimeKeys = indices.drop(1)
            if (rowIndexKey !in keyBytes.indices ||
                frameTimeKeys.any { index -> index !in keyBytes.indices } ||
                keyBytes.size <= 5
            ) {
                throw XApiException("X Web署名インデックスが範囲外です。", 502)
            }

            val frames = parseFrames(homeHtml)
            val frameIndex = unsigned(keyBytes[5]) % FRAME_VARIANT_COUNT
            if (frameIndex !in frames.indices) {
                throw XApiException("X Web署名アニメーションが不足しています。", 502)
            }
            val rows = frames[frameIndex]
            val rowIndex = unsigned(keyBytes[rowIndexKey]) % FRAME_ROW_COUNT
            if (rowIndex !in rows.indices) {
                throw XApiException("X Web署名アニメーション行が不足しています。", 502)
            }

            var frameTimeProduct = 1L
            for (index in frameTimeKeys) {
                val factor = unsigned(keyBytes[index]) % FRAME_ROW_COUNT
                if (factor != 0 && frameTimeProduct > Long.MAX_VALUE / factor) {
                    throw XApiException("X Web署名アニメーション時間が不正です。", 502)
                }
                frameTimeProduct *= factor
            }
            val roundedFrameTime = Math.round(frameTimeProduct / 10.0)
            if (roundedFrameTime > Long.MAX_VALUE / 10) {
                throw XApiException("X Web署名アニメーション時間が不正です。", 502)
            }
            val animationKey = animate(rows[rowIndex], roundedFrameTime * 10 / 4096.0)
            return SigningMaterial(keyBytes, animationKey)
        }

        internal fun encode(
            method: String,
            path: String,
            keyBytes: ByteArray,
            animationKey: String,
            timeNow: Long,
            randomByte: Int,
        ): String {
            if (!METHOD.matches(method) || path.isEmpty() || path.length > MAX_REQUEST_PATH_LENGTH) {
                throw XApiException("X Web署名入力が不正です。", 502)
            }
            if (keyBytes.size !in MIN_KEY_BYTES..MAX_KEY_BYTES || !ANIMATION_KEY.matches(animationKey)) {
                throw XApiException("X Web署名素材が不正です。", 502)
            }
            val data = "$method!$path!$timeNow$DEFAULT_KEYWORD$animationKey"
            val hash = try {
                MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
            } catch (exception: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256を利用できません。", exception)
            }
            val bytes = ByteArray(keyBytes.size + 4 + 16 + 1)
            keyBytes.copyInto(bytes)
            val offset = keyBytes.size
            bytes[offset] = timeNow.toByte()
            bytes[offset + 1] = (timeNow shr 8).toByte()
            bytes[offset + 2] = (timeNow shr 16).toByte()
            bytes[offset + 3] = (timeNow shr 24).toByte()
            hash.copyInto(bytes, destinationOffset = offset + 4, endIndex = 16)
            bytes[bytes.lastIndex] = ADDITIONAL_RANDOM_NUMBER.toByte()

            val mask = randomByte and 0xff
            val output = ByteArray(bytes.size + 1)
            output[0] = mask.toByte()
            bytes.forEachIndexed { index, value ->
                output[index + 1] = ((unsigned(value) xor mask) and 0xff).toByte()
            }
            return Base64.getEncoder().withoutPadding().encodeToString(output)
        }

        internal fun supportsOfficialApiRequest(url: HttpUrl): Boolean =
            url.isHttps &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.port == 443 &&
                url.host.lowercase(Locale.ROOT) in OFFICIAL_API_HOSTS &&
                url.encodedPath.isNotEmpty() &&
                url.encodedPath.length <= MAX_REQUEST_PATH_LENGTH

        private fun parseFrames(homeHtml: String): List<List<List<Int>>> {
            val frames = ArrayList<List<List<Int>>>()
            SVG.findAll(homeHtml).forEach { svgMatch ->
                if (frames.size >= MAX_ANIMATION_FRAMES) {
                    throw XApiException("X Web署名SVGが多すぎます。", 502)
                }
                val paths = PATH.findAll(svgMatch.groupValues[2]).map { it.groupValues[2] }.toList()
                if (paths.size < 2) {
                    throw XApiException("X Web署名SVGを解析できません。", 502)
                }
                val pathData = paths[1]
                if (pathData.length < 9 || pathData.length > MAX_ASSET_BYTES) {
                    throw XApiException("X Web署名SVGのパスが不正です。", 502)
                }
                val segments = CUBIC_SPLITTER.split(pathData.substring(9)).toList()
                if (segments.size > MAX_ANIMATION_ROWS) {
                    throw XApiException("X Web署名SVGの行数が不正です。", 502)
                }
                val rows = ArrayList<List<Int>>(segments.size)
                segments.forEach { segment ->
                    val cleaned = NON_DIGITS.replace(segment, " ").trim()
                    val values = if (cleaned.isEmpty()) {
                        emptyList()
                    } else {
                        cleaned.split(Regex("\\s+")).map { value ->
                            value.toIntOrNull()
                                ?: throw XApiException("X Web署名SVGを解析できません。", 502)
                        }
                    }
                    if (values.size > MAX_ANIMATION_ROW_VALUES) {
                        throw XApiException("X Web署名SVGの行が不正です。", 502)
                    }
                    rows += values
                }
                frames += rows
            }
            if (frames.isEmpty()) {
                throw XApiException("X Web署名アニメーションが見つかりません。", 502)
            }
            return frames
        }

        private fun animate(frame: List<Int>, targetTime: Double): String {
            if (frame.size < MIN_ANIMATION_ROW_VALUES) {
                throw XApiException("X Web署名アニメーションデータが不正です。", 502)
            }
            val fromColor = doubleArrayOf(frame[0].toDouble(), frame[1].toDouble(), frame[2].toDouble())
            val toColor = doubleArrayOf(frame[3].toDouble(), frame[4].toDouble(), frame[5].toDouble())
            val toRotation = solve(frame[6], 60.0, 360.0, true)
            val curves = DoubleArray(frame.size - 7) { index ->
                solve(frame[index + 7], if (index % 2 == 1) -1.0 else 0.0, 1.0, false)
            }

            val value = cubicValue(curves, targetTime)
            val output = StringBuilder()
            repeat(3) { index ->
                val color = max(fromColor[index] * (1 - value) + toColor[index] * value, 0.0)
                output.append(Math.round(color).toString(16))
            }

            val radians = toRotation * value * PI / 180
            val matrix = doubleArrayOf(
                cos(radians),
                -sin(radians),
                sin(radians),
                cos(radians),
            )
            matrix.forEach { matrixValue ->
                var rounded = Math.round(matrixValue * 100) / 100.0
                if (rounded < 0) {
                    rounded = -rounded
                }
                val hex = floatToHex(rounded).lowercase(Locale.ROOT)
                output.append(
                    when {
                        hex.startsWith('.') -> "0$hex"
                        hex.isEmpty() -> "0"
                        else -> hex
                    },
                )
            }
            output.append("00")
            return output.toString().replace(".", "").replace("-", "")
        }

        private fun solve(value: Int, minimum: Double, maximum: Double, floorValue: Boolean): Double {
            val result = value * (maximum - minimum) / 255 + minimum
            return if (floorValue) floor(result) else round(result * 100) / 100
        }

        private fun cubicValue(curves: DoubleArray, time: Double): Double {
            if (curves.size < 4) {
                throw XApiException("X Web署名カーブが不足しています。", 502)
            }
            if (time <= 0) {
                val gradient = if (curves[0] > 0) {
                    curves[1] / curves[0]
                } else if (curves[1] == 0.0 && curves[2] > 0) {
                    curves[3] / curves[2]
                } else {
                    0.0
                }
                return gradient * time
            }
            if (time >= 1) {
                val gradient = if (curves[2] < 1) {
                    (curves[3] - 1) / (curves[2] - 1)
                } else if (curves[2] == 1.0 && curves[0] < 1) {
                    (curves[1] - 1) / (curves[0] - 1)
                } else {
                    0.0
                }
                return 1 + gradient * (time - 1)
            }

            var start = 0.0
            var middle = 0.0
            var end = 1.0
            for (iteration in 0 until 100) {
                middle = (start + end) / 2
                val estimate = cubicCoordinate(curves[0], curves[2], middle)
                if (kotlin.math.abs(time - estimate) < 0.00001) {
                    break
                }
                if (estimate < time) {
                    start = middle
                } else {
                    end = middle
                }
            }
            return cubicCoordinate(curves[1], curves[3], middle)
        }

        private fun cubicCoordinate(a: Double, b: Double, middle: Double): Double =
            3 * a * (1 - middle) * (1 - middle) * middle +
                3 * b * (1 - middle) * middle * middle +
                middle * middle * middle

        private fun floatToHex(initialValue: Double): String {
            var value = initialValue
            var quotient = floor(value)
            var fraction = value - quotient
            val result = StringBuilder()
            while (quotient > 0) {
                val nextQuotient = floor(value / 16)
                val remainder = floor(value - nextQuotient * 16).toInt()
                result.insert(0, Character.forDigit(remainder, 16))
                value = nextQuotient
                quotient = nextQuotient
            }
            if (fraction == 0.0) {
                return result.toString()
            }
            result.append('.')
            repeat(32) {
                if (fraction <= 0) {
                    return@repeat
                }
                fraction *= 16
                val integer = floor(fraction).toInt()
                fraction -= integer
                result.append(Character.forDigit(integer, 16))
            }
            return result.toString()
        }

        private fun requireInputSize(value: String, source: String) {
            if (value.toByteArray(Charsets.UTF_8).size > MAX_ASSET_BYTES) {
                throw XApiException("${source}が上限サイズを超えています。", 502)
            }
        }

        private fun requireOfficialUrl(url: HttpUrl, expectedHost: String? = null) {
            require(url.isHttps) { "X公式Web資産URLはHTTPSである必要があります。" }
            require(url.username.isEmpty() && url.password.isEmpty()) {
                "X公式Web資産URLにユーザー情報は指定できません。"
            }
            require(url.port == 443) { "X公式Web資産URLのportが不正です。" }
            val host = url.host.lowercase(Locale.ROOT)
            require(host == X_WEB_HOST || host == ASSET_HOST) {
                "X公式Web以外の資産URLは利用できません。"
            }
            require(expectedHost == null || host == expectedHost) {
                "X公式Web資産URLのhostが不正です。"
            }
        }

        private fun unsigned(value: Byte): Int = value.toInt() and 0xff
    }
}
