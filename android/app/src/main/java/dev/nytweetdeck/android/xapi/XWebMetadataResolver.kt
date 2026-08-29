package dev.nytweetdeck.android.xapi

import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves the current public X Web GraphQL metadata without executing JavaScript.
 *
 * The resolver never accepts a non-official URL. Its [AssetFetcher] boundary is injectable so
 * callers can use OkHttp in production and deterministic official-asset fixtures in JVM tests.
 */
class XWebMetadataResolver(
    private val fetcher: AssetFetcher,
    private val homeUrl: HttpUrl = WEB_HOME,
    private val assetBaseUrl: HttpUrl = ASSET_BASE,
) {
    init {
        requireOfficialUrl(homeUrl, X_WEB_HOST)
        require(homeUrl.encodedPath == HOME_PATH && homeUrl.query == null) {
            "X公式WebホームURLが不正です。"
        }
        requireOfficialUrl(assetBaseUrl, ASSET_HOST)
        require(assetBaseUrl.encodedPath == ASSET_BASE_PATH && assetBaseUrl.query == null) {
            "X公式Web資産ベースURLが不正です。"
        }
    }

    constructor(
        client: OkHttpClient,
        userAgent: String,
        homeUrl: HttpUrl = WEB_HOME,
        assetBaseUrl: HttpUrl = ASSET_BASE,
    ) : this(OkHttpAssetFetcher(client, userAgent), homeUrl, assetBaseUrl)

    /** Resolves every GraphQL operation presently required by [profile]. */
    fun resolve(profile: XApiProfile): ResolvedMetadata = resolve(
        profile.operations.values.mapTo(LinkedHashSet()) { operation -> operation.operationName },
    )

    /**
     * Resolves the requested X Web operation names. A result is returned only when all of them
     * were found in official assets; partial metadata is rejected.
     */
    fun resolve(requiredOperationNames: Set<String>): ResolvedMetadata {
        val required = normalizeRequiredOperationNames(requiredOperationNames)
        val html = fetch(homeUrl, "X公式Webページ")
        val defaultsFromPage = parseBooleanFeatures(html)
        val operations = LinkedHashMap<String, ResolvedOperation>()
        var sourceVersion: String? = null

        for (scriptUrl in parseScriptUrls(html)) {
            val javascript = fetch(scriptUrl, "X公式Web JavaScript資産")
            parseOperations(javascript).forEach { (name, operation) ->
                operations.putIfAbsent(name, operation)
            }
            val fileName = scriptUrl.encodedPath.substringAfterLast('/')
            if (fileName.startsWith("main.")) {
                sourceVersion = fileName
            }
        }

        val candidates = parseChunkCandidates(html)
            .asSequence()
            .filter { candidate -> isRelevant(candidate.name) }
            .sortedWith(compareBy<ChunkCandidate> { priority(it) }.thenBy { it.name })
            .toList()
        for (candidate in candidates) {
            if (operations.keys.containsAll(required)) {
                break
            }
            fetchChunk(candidate)?.let { javascript ->
                parseOperations(javascript).forEach { (name, operation) ->
                    operations.putIfAbsent(name, operation)
                }
            }
        }

        val missing = required.filterNot(operations::containsKey)
        if (missing.isNotEmpty()) {
            throw XApiException(
                "X公式Web資産に必須operationがありません: ${missing.joinToString(", ")}",
                502,
            )
        }

        val selectedOperations = LinkedHashMap<String, ResolvedOperation>()
        val allFeatureKeys = LinkedHashSet<String>()
        for (name in required) {
            val operation = checkNotNull(operations[name])
            selectedOperations[name] = operation
            allFeatureKeys += operation.featureKeys
        }
        val featureDefaults = LinkedHashMap<String, Boolean>()
        for (key in allFeatureKeys) {
            featureDefaults[key] = defaultsFromPage[key] ?: false
        }
        return ResolvedMetadata(
            sourceVersion = sourceVersion,
            operationsByName = java.util.Map.copyOf(selectedOperations),
            allFeatureKeys = java.util.List.copyOf(allFeatureKeys),
            featureDefaults = java.util.Map.copyOf(featureDefaults),
        )
    }

    internal fun parseOperations(javascript: String): Map<String, ResolvedOperation> {
        requireInputSize(javascript, "X公式Web JavaScript資産")
        val operations = LinkedHashMap<String, ResolvedOperation>()
        OPERATION.findAll(javascript).forEach { match ->
            val operationId = match.groupValues[1]
            val operationName = match.groupValues[2]
            if (!SAFE_OPERATION_ID.matches(operationId) || !SAFE_OPERATION_NAME.matches(operationName)) {
                return@forEach
            }
            val featureKeys = parseMetadataList(match.groupValues[4]) ?: return@forEach
            val fieldToggles = parseMetadataList(match.groupValues[5]) ?: return@forEach
            val type = when (match.groupValues[3]) {
                "query" -> XApiProfile.OperationType.QUERY
                "mutation" -> XApiProfile.OperationType.MUTATION
                else -> return@forEach
            }
            operations.putIfAbsent(
                operationName,
                ResolvedOperation(
                    operationId = operationId,
                    operationName = operationName,
                    type = type,
                    featureKeys = java.util.List.copyOf(featureKeys),
                    fieldToggles = java.util.List.copyOf(fieldToggles),
                ),
            )
        }
        return java.util.Map.copyOf(operations)
    }

    internal fun parseBooleanFeatures(html: String): Map<String, Boolean> {
        requireInputSize(html, "X公式Webページ")
        val values = LinkedHashMap<String, Boolean>()
        BOOLEAN_FEATURE.findAll(html).forEach { match ->
            val key = match.groupValues[1]
            val value = parseBoolean(match.groupValues[2]) ?: return@forEach
            values[key] = value
        }
        return java.util.Map.copyOf(values)
    }

    internal fun parseChunkCandidates(html: String): List<ChunkCandidate> {
        requireInputSize(html, "X公式Webページ")
        val names = LinkedHashMap<String, String>()
        val hashes = LinkedHashMap<String, String>()
        MANIFEST_ENTRY.findAll(html).forEach { match ->
            val entryId = match.groupValues[1]
            val value = match.groupValues[2]
            when {
                CHUNK_HASH.matches(value) -> hashes[entryId] = value
                isSafeChunkName(value) -> names.putIfAbsent(entryId, value)
            }
        }
        return names.mapNotNull { (entryId, name) ->
            hashes[entryId]?.let { hash -> ChunkCandidate(name, hash) }
        }
    }

    private fun fetchChunk(candidate: ChunkCandidate): String? {
        for (suffix in CHUNK_SUFFIXES) {
            val url = resolveAssetUrl("${candidate.name}.${candidate.hash}$suffix")
            try {
                return fetch(url, "X公式Web JavaScript資産")
            } catch (exception: XApiException) {
                if (exception.statusCode != 404) {
                    throw exception
                }
            }
        }
        return null
    }

    private fun resolveAssetUrl(relativePath: String): HttpUrl {
        val url = assetBaseUrl.resolve(relativePath)
            ?: throw XApiException("X公式Web資産URLが不正です。", 502)
        requireOfficialUrl(url, ASSET_HOST)
        if (!url.encodedPath.startsWith(ASSET_BASE_PATH)) {
            throw XApiException("X公式Web資産URLが不正です。", 502)
        }
        return url
    }

    private fun fetch(url: HttpUrl, source: String): String {
        requireOfficialUrl(url)
        val body = try {
            fetcher.fetch(url)
        } catch (exception: XApiException) {
            throw exception
        } catch (exception: Exception) {
            throw XApiException("${source}の通信に失敗しました。", cause = exception)
        }
        if (body.toByteArray(Charsets.UTF_8).size > MAX_ASSET_BYTES) {
            throw XApiException("${source}が上限サイズを超えています。", 502)
        }
        return body
    }

    private fun parseScriptUrls(html: String): List<HttpUrl> {
        requireInputSize(html, "X公式Webページ")
        val urls = LinkedHashSet<HttpUrl>()
        SCRIPT_URL.findAll(html).forEach { match ->
            val url = match.value.toHttpUrlOrNull()
                ?: throw XApiException("X公式Web JavaScript資産URLが不正です。", 502)
            requireOfficialUrl(url, ASSET_HOST)
            if (!url.encodedPath.startsWith(ASSET_BASE_PATH)) {
                throw XApiException("X公式Web JavaScript資産URLが不正です。", 502)
            }
            urls += url
        }
        if (urls.isEmpty()) {
            throw XApiException("X公式WebのJavaScript資産が見つかりません。", 502)
        }
        return java.util.List.copyOf(urls)
    }

    private fun normalizeRequiredOperationNames(values: Set<String>): LinkedHashSet<String> {
        require(values.size in 1..MAX_REQUIRED_OPERATIONS) {
            "必須X Web operation数が不正です。"
        }
        return LinkedHashSet<String>(values.size).also { names ->
            values.forEach { name ->
                require(SAFE_OPERATION_NAME.matches(name)) { "必須X Web operation形式が不正です。" }
                names += name
            }
        }
    }

    private fun parseMetadataList(value: String): List<String>? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val values = ArrayList<String>()
        var index = 0
        while (index < trimmed.length) {
            if (trimmed[index] != '"') {
                return null
            }
            val end = trimmed.indexOf('"', startIndex = index + 1)
            if (end == -1) {
                return null
            }
            val key = trimmed.substring(index + 1, end)
            if (!SAFE_METADATA_KEY.matches(key)) {
                return null
            }
            values += key
            if (values.size > MAX_METADATA_KEYS) {
                return null
            }
            index = end + 1
            while (index < trimmed.length && trimmed[index].isWhitespace()) {
                index += 1
            }
            if (index == trimmed.length) {
                break
            }
            if (trimmed[index] != ',') {
                return null
            }
            index += 1
            while (index < trimmed.length && trimmed[index].isWhitespace()) {
                index += 1
            }
            if (index == trimmed.length) {
                return null
            }
        }
        return values.distinct()
    }

    private fun parseBoolean(value: String): Boolean? = try {
        (JSON.parseToJsonElement(value) as? JsonPrimitive)?.booleanOrNull
    } catch (_: Exception) {
        null
    }

    private fun isRelevant(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return RELEVANT_CHUNK_MARKERS.any(normalized::contains)
    }

    private fun priority(candidate: ChunkCandidate): Int = when {
        candidate.name.equals("bundle.loggedinmain", ignoreCase = true) -> 0
        candidate.name.contains("hometimeline", ignoreCase = true) ||
            candidate.name.contains("notifications", ignoreCase = true) -> 1
        candidate.name.contains("bookmark", ignoreCase = true) ||
            candidate.name.contains("history", ignoreCase = true) -> 2
        else -> 3
    }

    data class ResolvedOperation(
        val operationId: String,
        val operationName: String,
        val type: XApiProfile.OperationType,
        val featureKeys: List<String>,
        val fieldToggles: List<String>,
    )

    /** A fully validated snapshot that can atomically replace an [XApiProfile] value. */
    data class ResolvedMetadata(
        val sourceVersion: String?,
        val operationsByName: Map<String, ResolvedOperation>,
        val allFeatureKeys: List<String>,
        val featureDefaults: Map<String, Boolean>,
    ) {
        fun applyTo(profile: XApiProfile): XApiProfile {
            val operations = LinkedHashMap<String, XApiProfile.GraphQlOperation>()
            val missing = LinkedHashSet<String>()
            profile.operations.forEach { (purpose, current) ->
                val resolved = operationsByName[current.operationName]
                if (resolved == null) {
                    missing += current.operationName
                    return@forEach
                }
                operations[purpose] = XApiProfile.GraphQlOperation(
                    operationId = resolved.operationId,
                    operationName = resolved.operationName,
                    type = resolved.type,
                    featureKeys = resolved.featureKeys,
                    fieldToggles = resolved.fieldToggles,
                )
            }
            if (missing.isNotEmpty()) {
                throw XApiException(
                    "必須X Web operationが見つかりません: ${missing.joinToString(", ")}",
                    502,
                )
            }
            val defaults = LinkedHashMap<String, Boolean>()
            allFeatureKeys.forEach { key ->
                val value = featureDefaults[key]
                    ?: throw XApiException("X Web Feature Switch既定値がありません: $key", 502)
                defaults[key] = value
            }
            return profile.copy(
                featureKeys = java.util.List.copyOf(allFeatureKeys),
                featureDefaults = java.util.Map.copyOf(defaults),
                operations = java.util.Map.copyOf(operations),
            )
        }
    }

    internal data class ChunkCandidate(
        val name: String,
        val hash: String,
    )

    fun interface AssetFetcher {
        fun fetch(url: HttpUrl): String
    }

    private class OkHttpAssetFetcher(
        client: OkHttpClient,
        userAgent: String,
    ) : AssetFetcher {
        private val client = client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        private val userAgent = userAgent.trim().also {
            require(it.isNotEmpty()) { "X Web User-Agentが空です。" }
        }

        override fun fetch(url: HttpUrl): String {
            val request = Request.Builder()
                .url(url)
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

    private companion object {
        const val MAX_ASSET_BYTES = 8 * 1024 * 1024
        const val MAX_REQUIRED_OPERATIONS = 100
        const val MAX_METADATA_KEYS = 500
        const val X_WEB_HOST = "x.com"
        const val ASSET_HOST = "abs.twimg.com"
        const val HOME_PATH = "/home"
        const val ASSET_BASE_PATH = "/responsive-web/client-web/"

        val WEB_HOME = "https://x.com/home".toHttpUrl()
        val ASSET_BASE = "https://abs.twimg.com$ASSET_BASE_PATH".toHttpUrl()
        val SCRIPT_URL = Regex(
            """https://abs\.twimg\.com/responsive-web/client-web/[^"'<>\s]+\.js""",
        )
        val MANIFEST_ENTRY = Regex("(?:^|[,{])(\\d+):\"([^\"]+)\"")
        val BOOLEAN_FEATURE = Regex("\"([A-Za-z][A-Za-z0-9_]{0,199})\":\\{\"value\":(true|false)")
        val OPERATION = Regex(
            """queryId:\s*"([^"]+)"\s*,\s*operationName:\s*"([^"]+)"\s*,\s*operationType:\s*"(query|mutation)"\s*,\s*metadata:\s*\{\s*featureSwitches:\s*\[([^]]*)]\s*,\s*fieldToggles:\s*\[([^]]*)]\s*}""",
        )
        val SAFE_OPERATION_ID = Regex("[A-Za-z0-9_-]{8,100}")
        val SAFE_OPERATION_NAME = Regex("[A-Za-z][A-Za-z0-9_]{1,100}")
        val SAFE_METADATA_KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,199}")
        val CHUNK_HASH = Regex("[0-9a-f]{16}")
        val SAFE_CHUNK_NAME = Regex("[A-Za-z0-9_./~-]{1,220}")
        val CHUNK_SUFFIXES = listOf("a.js", ".js")
        val RELEVANT_CHUNK_MARKERS = listOf(
            "loggedinmain",
            "hometimeline",
            "notifications",
            "bookmark",
            "history",
            "explore",
            "userprofile",
            "conversation",
            "birdwatch",
            "search",
            "tweet",
        )
        val JSON = Json { isLenient = false }

        fun requireInputSize(value: String, source: String) {
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_ASSET_BYTES) {
                "$source が上限サイズを超えています。"
            }
        }

        fun isSafeChunkName(value: String): Boolean = SAFE_CHUNK_NAME.matches(value) &&
            !value.startsWith('/') &&
            !value.endsWith('/') &&
            !value.contains("..") &&
            !value.contains("//")

        fun requireOfficialUrl(url: HttpUrl, expectedHost: String? = null) {
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
    }
}
