package dev.nytweetdeck.android.xapi.live

import dev.nytweetdeck.android.data.AccountSecrets
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class LivePipelineSubscriptionService(
    private val client: LivePipelineSessionClient,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val accounts = LinkedHashMap<String, AccountState>()
    private val listeners = ConcurrentHashMap<Long, (LivePipelineEvent) -> Unit>()
    private val listenerIds = AtomicLong()
    private val mutableEvents = MutableSharedFlow<LivePipelineEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    val events: SharedFlow<LivePipelineEvent> = mutableEvents.asSharedFlow()

    @Synchronized
    fun update(
        account: AccountSecrets,
        subscriberId: String,
        postIds: List<String>,
        directMessages: Boolean,
    ): LivePipelineSubscriptionStatus {
        require(SUBSCRIBER_ID.matches(subscriberId)) { "Live Pipelineの購読ID形式が不正です。" }
        val requestedTopics = topicsFor(account, postIds, directMessages)
        val state = accounts.getOrPut(account.accountId) { AccountState(account) }
        val candidateSubscribers = LinkedHashMap(state.subscribers)
        if (requestedTopics.isEmpty()) {
            candidateSubscribers.remove(subscriberId)
        } else {
            candidateSubscribers[subscriberId] = requestedTopics
        }
        val mergedTopics = mergedTopics(candidateSubscribers)
        require(mergedTopics.count { topic -> topic.startsWith(TWEET_TOPIC_PREFIX) } <= MAX_POST_TOPICS) {
            "Live Pipelineの表示ポスト購読数は100件以下にしてください。"
        }
        val accountChanged = state.account != account
        state.account = account
        state.subscribers.clear()
        state.subscribers.putAll(candidateSubscribers)
        reconnectIfChanged(account.accountId, state, mergedTopics, accountChanged)
        if (state.subscribers.isEmpty()) {
            accounts.remove(account.accountId)
        }
        return status(account.accountId, state)
    }

    @Synchronized
    fun remove(accountId: String, subscriberId: String) {
        require(SUBSCRIBER_ID.matches(subscriberId)) { "Live Pipelineの購読ID形式が不正です。" }
        val state = accounts[accountId] ?: return
        state.subscribers.remove(subscriberId)
        val mergedTopics = mergedTopics(state.subscribers)
        reconnectIfChanged(accountId, state, mergedTopics, forceReconnect = false)
        if (state.subscribers.isEmpty()) {
            accounts.remove(accountId)
        }
    }

    @Synchronized
    fun closeAccount(accountId: String) {
        accounts.remove(accountId)?.connection?.close()
    }

    fun addListener(listener: (LivePipelineEvent) -> Unit): AutoCloseable {
        val id = listenerIds.incrementAndGet()
        listeners[id] = listener
        return AutoCloseable { listeners.remove(id) }
    }

    override fun close() {
        closeAll()
    }

    @Synchronized
    fun closeAll() {
        accounts.values.forEach { state -> state.connection?.close() }
        accounts.clear()
    }

    private fun reconnectIfChanged(
        accountId: String,
        state: AccountState,
        topics: Set<String>,
        forceReconnect: Boolean,
    ) {
        if (!forceReconnect && topics == state.connectedTopics) {
            return
        }
        state.connection?.close()
        state.connection = null
        state.connectedTopics = topics
        state.lastErrorKind = null
        if (topics.isEmpty()) {
            return
        }
        state.connection = client.open(state.account, topics) { event -> onEvent(accountId, event) }
    }

    private fun onEvent(accountId: String, event: LivePipelineEvent) {
        synchronized(this) {
            val state = accounts[accountId] ?: return
            if (event.accountId != accountId) {
                return
            }
            state.lastEventAt = event.occurredAt
            state.lastErrorKind = (event as? LivePipelineEvent.Error)?.kind
        }
        publish(event)
    }

    private fun publish(event: LivePipelineEvent) {
        mutableEvents.tryEmit(event)
        listeners.values.toList().forEach { listener ->
            runCatching { listener(event) }
        }
    }

    private fun topicsFor(
        account: AccountSecrets,
        postIds: List<String>,
        directMessages: Boolean,
    ): Set<String> {
        val topics = linkedSetOf<String>()
        postIds.forEach { postId ->
            require(POST_ID.matches(postId)) { "Live PipelineのポストID形式が不正です。" }
            topics += "$TWEET_TOPIC_PREFIX$postId"
        }
        require(topics.size <= MAX_POST_TOPICS) { "Live Pipelineの表示ポスト購読数は100件以下にしてください。" }
        if (directMessages) {
            require(POST_ID.matches(account.userId)) { "Live PipelineのユーザーID形式が不正です。" }
            topics += "$DM_UPDATE_TOPIC_PREFIX${account.userId}"
            topics += "$DM_TYPING_TOPIC_PREFIX${account.userId}"
        }
        return topics.toSortedSet()
    }

    private fun mergedTopics(subscribers: Map<String, Set<String>>): Set<String> = subscribers.values
        .asSequence()
        .flatMap { topics -> topics.asSequence() }
        .toSortedSet()

    private fun status(accountId: String, state: AccountState): LivePipelineSubscriptionStatus =
        LivePipelineSubscriptionStatus(
            accountId = accountId,
            connected = state.connection != null,
            topicCount = state.connectedTopics.size,
            lastErrorKind = state.lastErrorKind,
            lastEventAt = state.lastEventAt,
        )

    private class AccountState(
        var account: AccountSecrets,
    ) {
        val subscribers = LinkedHashMap<String, Set<String>>()
        var connectedTopics: Set<String> = emptySet()
        var connection: LivePipelineConnection? = null
        var lastErrorKind: LivePipelineErrorKind? = null
        var lastEventAt: java.time.Instant? = null
    }

    private companion object {
        const val MAX_POST_TOPICS = 100
        const val EVENT_BUFFER_CAPACITY = 256
        const val TWEET_TOPIC_PREFIX = "/tweet_engagement/"
        const val DM_UPDATE_TOPIC_PREFIX = "/dm_update/"
        const val DM_TYPING_TOPIC_PREFIX = "/dm_typing/"
        val POST_ID = Regex("[0-9]{1,30}")
        val SUBSCRIBER_ID = Regex("[A-Za-z0-9._:-]{1,200}")
    }
}
