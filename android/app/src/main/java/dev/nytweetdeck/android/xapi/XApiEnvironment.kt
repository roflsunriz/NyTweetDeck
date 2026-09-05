package dev.nytweetdeck.android.xapi

import android.content.Context
import android.webkit.WebSettings
import dev.nytweetdeck.android.model.CapturedWebSession
import dev.nytweetdeck.android.xapi.live.LivePipelineClient
import dev.nytweetdeck.android.xapi.live.LivePipelineSubscriptionService
import java.util.Locale
import okhttp3.OkHttpClient

data class VerifiedWebSession(
    val profileName: String,
    val account: VerifiedXAccount,
    val credentials: XSessionCredentials,
)

fun interface XSessionVerifier {
    fun verify(session: CapturedWebSession): VerifiedWebSession
}

class XApiEnvironment(context: Context) : XSessionVerifier, XApiMetadataRefresher {
    private val applicationContext = context.applicationContext
    private val userAgent: String by lazy {
        WebSettings.getDefaultUserAgent(applicationContext).takeIf(String::isNotBlank)
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
    private val httpClient by lazy { OkHttpClient() }
    private val metadataUserAgent by lazy { normalizeMetadataUserAgent(userAgent) }
    private val bundledProfile: XApiProfile by lazy {
        val profileJson = applicationContext.assets.open("web-current.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val defaultsJson = applicationContext.assets.open("web-boolean-feature-defaults.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        XApiProfile.parse(profileJson, defaultsJson)
    }
    @Volatile private var metadataResolved: Boolean? = null
    private val metadataStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        XApiMetadataStore(bundledProfile) {
            XWebMetadataResolver(httpClient, metadataUserAgent).resolve(bundledProfile)
        }
    }
    private val bearerResolver by lazy { XWebBearerResolver(httpClient, userAgent) }
    private val transactionIdService by lazy { XClientTransactionIdService(httpClient, userAgent) }
    private val graphQlClient by lazy {
        AuthenticatedGraphQlClient(httpClient, metadataStore::currentProfile, userAgent, transactionIdService)
    }
    private val restClient by lazy {
        AuthenticatedRestClient(httpClient, metadataStore::currentProfile, userAgent, transactionIdService)
    }
    private val livePipelineClient by lazy {
        LivePipelineClient(httpClient, metadataStore::currentProfile, userAgent)
    }
    private val livePipelineSubscriptions by lazy {
        LivePipelineSubscriptionService(livePipelineClient)
    }
    private val accountVerifier by lazy { XAccountVerifier(graphQlClient) }

    override fun verify(session: CapturedWebSession): VerifiedWebSession {
        val credentials = XSessionCredentials(
            bearerToken = bearerResolver.require(),
            authToken = session.authToken,
            csrfToken = session.csrfToken,
        )
        val account = accountVerifier.verify(
            credentials = credentials,
            expectedUserId = session.userId,
            language = Locale.getDefault().toLanguageTag().ifBlank { "ja" },
        )
        return VerifiedWebSession(session.profileName, account, credentials)
    }

    fun graphQlClient(): AuthenticatedGraphQlClient = graphQlClient

    fun restClient(): AuthenticatedRestClient = restClient

    fun translateCommunityNote(account: dev.nytweetdeck.android.data.AccountSecrets, noteId: String, language: String) =
        restClient.translateCommunityNote(account, noteId, language)

    fun livePipeline(): LivePipelineSubscriptionService = livePipelineSubscriptions

    override fun refreshMetadata(): XApiMetadataRefreshResult =
        metadataStore.refreshMetadata().also { metadataResolved = it.succeeded }

    fun metadataResolutionSucceeded(): Boolean? = metadataResolved
}

internal fun normalizeMetadataUserAgent(userAgent: String): String = userAgent
    .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s+Version/4\\.0(?=\\s)", RegexOption.IGNORE_CASE), "")
    .trim()
