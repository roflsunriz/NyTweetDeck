package dev.nytweetdeck.android.xapi.live

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.xapi.XApiException
import dev.nytweetdeck.android.xapi.XApiProfile
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

fun interface LivePipelineDelay {
    fun pause(duration: Duration)
}

class LivePipelineClient(
    private val connector: LivePipelineConnector,
    private val profileProvider: () -> XApiProfile,
    userAgent: String,
    private val parser: LivePipelineEventParser = LivePipelineEventParser(),
    private val clock: Clock = Clock.systemUTC(),
    private val delay: LivePipelineDelay = SystemLivePipelineDelay,
) : LivePipelineSessionClient {
    private val userAgent = userAgent.trim().also {
        require(it.isNotEmpty() && !it.contains('\r') && !it.contains('\n')) {
            "X Web User-Agentが不正です。"
        }
    }

    constructor(
        client: OkHttpClient,
        profileProvider: () -> XApiProfile,
        userAgent: String,
        parser: LivePipelineEventParser = LivePipelineEventParser(),
        clock: Clock = Clock.systemUTC(),
        delay: LivePipelineDelay = SystemLivePipelineDelay,
    ) : this(
        connector = OkHttpLivePipelineConnector(client),
        profileProvider = profileProvider,
        userAgent = userAgent,
        parser = parser,
        clock = clock,
        delay = delay,
    )

    override fun open(
        account: AccountSecrets,
        topics: Set<String>,
        eventConsumer: (LivePipelineEvent) -> Unit,
    ): LivePipelineConnection {
        val allTopics = normalizeTopics(topics)
        val initialTopics = allTopics.take(MAX_AUTO_SUBSCRIBE_TOPICS).toSet()
        val state = ConnectionState()
        val worker = Thread(
            {
                runConnection(account, allTopics, initialTopics, eventConsumer, state)
            },
            "nytweetdeck-live-pipeline-${account.accountId}",
        ).apply {
            isDaemon = true
        }
        state.worker = worker
        worker.start()
        return state
    }

    internal fun createEventRequest(account: AccountSecrets, topics: Set<String>): Request {
        require(topics.size in 1..MAX_AUTO_SUBSCRIBE_TOPICS) { "Live Pipelineの初期topic数が不正です。" }
        val normalizedTopics = normalizeTopics(topics)
        val url = resolveEndpoint(EVENTS_ENDPOINT).newBuilder()
            .addQueryParameter("topic", normalizedTopics.joinToString(","))
            .build()
        return authenticatedRequestBuilder(url, account)
            .header("Accept", "text/event-stream")
            .get()
            .build()
    }

    private fun createSubscriptionRequest(
        account: AccountSecrets,
        sessionId: String,
        topics: Set<String>,
    ): Request {
        require(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_LENGTH) {
            "Live PipelineのセッションIDが不正です。"
        }
        val normalizedTopics = normalizeTopics(topics)
        val form = FormBody.Builder()
            .add("sub_topics", normalizedTopics.joinToString(","))
            .add("unsub_topics", "")
            .build()
        return authenticatedRequestBuilder(resolveEndpoint(UPDATE_SUBSCRIPTIONS_ENDPOINT), account)
            .header("LivePipeline-Session", sessionId)
            .post(form)
            .build()
    }

    private fun runConnection(
        account: AccountSecrets,
        allTopics: Set<String>,
        initialTopics: Set<String>,
        eventConsumer: (LivePipelineEvent) -> Unit,
        state: ConnectionState,
    ) {
        var reconnectDelay = MIN_RECONNECT_DELAY
        while (!state.closed.get() && !state.terminal.get()) {
            try {
                val stream = connector.connect(createEventRequest(account, initialTopics))
                state.stream = stream
                reconnectDelay = MIN_RECONNECT_DELAY
                stream.readDataLines { body ->
                    handleDataLine(account, allTopics, initialTopics, body, eventConsumer, state)
                }
            } catch (exception: LivePipelineHttpException) {
                if (!state.closed.get()) {
                    emitError(account, eventConsumer, LivePipelineErrorKind.CONNECTION, exception, reconnectDelay)
                    if (exception.statusCode in 400..499) {
                        state.terminal.set(true)
                        break
                    }
                }
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!state.closed.get()) {
                    emitError(
                        account,
                        eventConsumer,
                        LivePipelineErrorKind.CONNECTION,
                        RuntimeException("Live Pipeline接続スレッドが中断されました。", exception),
                        reconnectDelay,
                    )
                }
                return
            } catch (exception: RuntimeException) {
                if (!state.closed.get()) {
                    emitError(account, eventConsumer, LivePipelineErrorKind.CONNECTION, exception, reconnectDelay)
                }
            } finally {
                state.stopRenewal()
                state.stream?.close()
                state.stream = null
            }
            if (!state.closed.get() && !state.terminal.get()) {
                try {
                    delay.pause(reconnectDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (exception: RuntimeException) {
                    if (!state.closed.get()) {
                        emitError(account, eventConsumer, LivePipelineErrorKind.CONNECTION, exception, null)
                    }
                    return
                }
                reconnectDelay = reconnectDelay.multipliedBy(2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        }
    }

    private fun handleDataLine(
        account: AccountSecrets,
        allTopics: Set<String>,
        initialTopics: Set<String>,
        body: String,
        eventConsumer: (LivePipelineEvent) -> Unit,
        state: ConnectionState,
    ) {
        val event = try {
            parser.parse(body)
        } catch (exception: RuntimeException) {
            emitError(account, eventConsumer, LivePipelineErrorKind.PROTOCOL, exception, null)
            return
        }
        when (event) {
            is ParsedLivePipelineEvent.SystemConfig -> {
                try {
                    val remainingTopics = allTopics - initialTopics
                    if (remainingTopics.isNotEmpty()) {
                        connector.updateSubscriptions(
                            createSubscriptionRequest(account, event.config.sessionId, remainingTopics),
                        )
                    }
                    scheduleRenewal(account, allTopics, event.config, eventConsumer, state)
                    eventConsumer(
                        LivePipelineEvent.Connected(
                            accountId = account.accountId,
                            sessionId = event.config.sessionId,
                            subscriptionTtlMilliseconds = event.config.subscriptionTtlMilliseconds,
                            occurredAt = clock.instant(),
                        ),
                    )
                } catch (exception: RuntimeException) {
                    emitError(account, eventConsumer, LivePipelineErrorKind.SUBSCRIPTION, exception, null)
                    if ((exception as? LivePipelineHttpException)?.statusCode in 400..499) {
                        state.terminal.set(true)
                    }
                    state.stream?.close()
                }
            }
            is ParsedLivePipelineEvent.TweetEngagement -> eventConsumer(
                LivePipelineEvent.Engagement(
                    accountId = account.accountId,
                    postId = event.postId,
                    counts = event.counts,
                    occurredAt = clock.instant(),
                ),
            )
            is ParsedLivePipelineEvent.DirectMessageUpdate -> eventConsumer(
                LivePipelineEvent.DirectMessageUpdate(
                    accountId = account.accountId,
                    userId = event.userId,
                    payload = event.payload,
                    occurredAt = clock.instant(),
                ),
            )
            is ParsedLivePipelineEvent.DirectMessageTyping -> eventConsumer(
                LivePipelineEvent.DirectMessageTyping(
                    accountId = account.accountId,
                    userId = event.userId,
                    payload = event.payload,
                    occurredAt = clock.instant(),
                ),
            )
            is ParsedLivePipelineEvent.LiveContent -> eventConsumer(
                LivePipelineEvent.LiveContent(
                    accountId = account.accountId,
                    entityId = event.entityId,
                    payload = event.payload,
                    occurredAt = clock.instant(),
                ),
            )
        }
    }

    private fun scheduleRenewal(
        account: AccountSecrets,
        topics: Set<String>,
        config: LivePipelineSessionConfig,
        eventConsumer: (LivePipelineEvent) -> Unit,
        state: ConnectionState,
    ) {
        state.stopRenewal()
        val renewal = Thread(
            {
                while (!state.closed.get() && !state.terminal.get()) {
                    val renewAt = clock.instant().plusMillis(
                        (config.subscriptionTtlMilliseconds - RENEWAL_MARGIN_MILLISECONDS)
                            .coerceAtLeast(MIN_RENEWAL_DELAY_MILLISECONDS),
                    )
                    try {
                        delay.pause(Duration.between(clock.instant(), renewAt))
                        if (!state.closed.get()) {
                            connector.updateSubscriptions(
                                createSubscriptionRequest(account, config.sessionId, topics),
                            )
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (exception: RuntimeException) {
                        if (!state.closed.get()) {
                            emitError(account, eventConsumer, LivePipelineErrorKind.SUBSCRIPTION, exception, null)
                            if ((exception as? LivePipelineHttpException)?.statusCode in 400..499) {
                                state.terminal.set(true)
                            }
                            state.stream?.close()
                        }
                        break
                    }
                }
            },
            "nytweetdeck-live-pipeline-renewal-${account.accountId}",
        ).apply {
            isDaemon = true
        }
        state.renewal = renewal
        renewal.start()
    }

    private fun resolveEndpoint(endpointKey: String): HttpUrl {
        val path = profileProvider().restEndpoints[endpointKey]
            ?: throw XApiException("${endpointKey}エンドポイントが未定義です。", 503)
        require(path.startsWith('/') && !path.contains("..") && path.length <= MAX_ENDPOINT_PATH_LENGTH) {
            "Live Pipelineエンドポイントが不正です。"
        }
        val url = LIVE_API_BASE.resolve(path)
            ?: throw XApiException("Live Pipeline URLが不正です。", 502)
        require(url.isHttps && url.host.equals(LIVE_API_HOST, ignoreCase = true) && url.port == 443) {
            "Live Pipeline URLは公式HTTPS APIである必要があります。"
        }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Live Pipeline URLが不正です。"
        }
        return url
    }

    private fun authenticatedRequestBuilder(url: HttpUrl, account: AccountSecrets): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${account.webBearerToken}")
        .header("Cookie", "auth_token=${account.authToken}; ct0=${account.csrfToken}")
        .header("X-CSRF-Token", account.csrfToken)
        .header("X-Twitter-Auth-Type", "OAuth2Session")
        .header("X-Twitter-Active-User", "yes")
        .header("X-Twitter-Client-Language", "ja")
        .header("Accept-Language", "ja")
        .header("Origin", "https://x.com")
        .header("Referer", "https://x.com/")
        .header("User-Agent", userAgent)

    private fun normalizeTopics(topics: Set<String>): Set<String> {
        require(topics.isNotEmpty() && topics.size <= MAX_TOTAL_TOPICS) {
            "Live Pipelineのtopic数が不正です。"
        }
        return topics.toSortedSet().also { values ->
            values.forEach { topic ->
                require(TOPIC_PATTERN.matches(topic)) { "Live Pipelineのtopic形式が不正です。" }
            }
        }
    }

    private fun emitError(
        account: AccountSecrets,
        eventConsumer: (LivePipelineEvent) -> Unit,
        kind: LivePipelineErrorKind,
        exception: RuntimeException,
        retryDelay: Duration?,
    ) {
        eventConsumer(
            LivePipelineEvent.Error(
                accountId = account.accountId,
                kind = kind,
                statusCode = (exception as? LivePipelineHttpException)?.statusCode,
                retryDelayMilliseconds = retryDelay?.toMillis(),
                occurredAt = clock.instant(),
            ),
        )
    }

    private inner class ConnectionState : LivePipelineConnection {
        val closed = AtomicBoolean()
        val terminal = AtomicBoolean()

        @Volatile
        var stream: LivePipelineStream? = null

        @Volatile
        var worker: Thread? = null

        @Volatile
        var renewal: Thread? = null

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                stream?.close()
                worker?.interrupt()
                stopRenewal()
            }
        }

        fun stopRenewal() {
            renewal?.interrupt()
            renewal = null
        }
    }

    private object SystemLivePipelineDelay : LivePipelineDelay {
        override fun pause(duration: Duration) {
            if (duration.isNegative || duration.isZero) {
                return
            }
            try {
                Thread.sleep(duration.toMillis())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LivePipelineHttpException(0, "Live Pipelineの待機が中断されました。", exception)
            }
        }
    }

    private companion object {
        const val EVENTS_ENDPOINT = "livePipelineEvents"
        const val UPDATE_SUBSCRIPTIONS_ENDPOINT = "livePipelineUpdateSubscriptions"
        const val LIVE_API_HOST = "api.x.com"
        const val MAX_AUTO_SUBSCRIBE_TOPICS = 20
        const val MAX_TOTAL_TOPICS = 102
        const val MAX_SESSION_ID_LENGTH = 500
        const val MAX_ENDPOINT_PATH_LENGTH = 300
        const val RENEWAL_MARGIN_MILLISECONDS = 20_000L
        const val MIN_RENEWAL_DELAY_MILLISECONDS = 1_000L
        val MIN_RECONNECT_DELAY: Duration = Duration.ofMillis(500)
        val MAX_RECONNECT_DELAY: Duration = Duration.ofSeconds(16)
        val LIVE_API_BASE: HttpUrl = "https://api.x.com".toHttpUrl()
        val TOPIC_PATTERN = Regex("/(?:tweet_engagement|dm_update|dm_typing)/[0-9]{1,30}")
    }
}
