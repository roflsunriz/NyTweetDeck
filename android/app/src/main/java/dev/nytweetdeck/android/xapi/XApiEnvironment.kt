package dev.nytweetdeck.android.xapi

import android.content.Context
import android.webkit.WebSettings
import dev.nytweetdeck.android.model.CapturedWebSession
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

class XApiEnvironment(context: Context) : XSessionVerifier {
    private val applicationContext = context.applicationContext
    private val userAgent: String by lazy {
        WebSettings.getDefaultUserAgent(applicationContext).takeIf(String::isNotBlank)
            ?: throw XApiException("Android WebViewのUser-Agentを取得できません。", 503)
    }
    private val httpClient by lazy { OkHttpClient() }
    private val bundledProfile: XApiProfile by lazy {
        val profileJson = applicationContext.assets.open("web-current.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val defaultsJson = applicationContext.assets.open("web-boolean-feature-defaults.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        XApiProfile.parse(profileJson, defaultsJson)
    }
    @Volatile
    private var metadataResolved: Boolean? = null
    private val activeProfile: XApiProfile by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            XWebMetadataResolver(httpClient, userAgent)
                .resolve(bundledProfile)
                .applyTo(bundledProfile)
        }.fold(
            onSuccess = {
                metadataResolved = true
                it
            },
            onFailure = {
                metadataResolved = false
                bundledProfile
            },
        )
    }
    private val bearerResolver by lazy { XWebBearerResolver(httpClient, userAgent) }
    private val transactionIdService by lazy { XClientTransactionIdService(httpClient, userAgent) }
    private val graphQlClient by lazy {
        AuthenticatedGraphQlClient(httpClient, { activeProfile }, userAgent, transactionIdService)
    }
    private val restClient by lazy {
        AuthenticatedRestClient(httpClient, { activeProfile }, userAgent, transactionIdService)
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

    fun metadataResolutionSucceeded(): Boolean? = metadataResolved
}
