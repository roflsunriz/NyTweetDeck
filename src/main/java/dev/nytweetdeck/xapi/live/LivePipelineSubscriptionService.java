package dev.nytweetdeck.xapi.live;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.timeline.TimelineEventBus;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import dev.nytweetdeck.account.vault.VaultLockedEvent;

@Service
public class LivePipelineSubscriptionService {

    private static final int MAX_POST_TOPICS = 100;

    private final LivePipelineConnector connector;
    private final LivePipelineEventParser eventParser;
    private final TimelineEventBus timelineEventBus;
    private final AccountVaultSessionManager vaultSessionManager;
    private final Map<String, AccountState> accounts = new HashMap<>();

    public LivePipelineSubscriptionService(
            LivePipelineConnector connector,
            LivePipelineEventParser eventParser,
            TimelineEventBus timelineEventBus,
            AccountVaultSessionManager vaultSessionManager) {
        this.connector = connector;
        this.eventParser = eventParser;
        this.timelineEventBus = timelineEventBus;
        this.vaultSessionManager = vaultSessionManager;
    }

    public synchronized SubscriptionStatus update(
            String accountId,
            String subscriberId,
            List<String> postIds,
            boolean directMessages) {
        validateSubscriberId(subscriberId);
        if (postIds.size() > MAX_POST_TOPICS) {
            throw new IllegalArgumentException("Live Pipelineのポスト購読数は100件以下にしてください。");
        }
        var topics = new HashSet<String>();
        for (var postId : postIds) {
            if (postId == null || !postId.matches("[0-9]{1,30}")) {
                throw new IllegalArgumentException("Live PipelineのポストID形式が不正です。");
            }
            topics.add("/tweet_engagement/" + postId);
        }
        if (directMessages) {
            var userId = vaultSessionManager.requireAccount(accountId).userId();
            topics.add("/dm_update/" + userId);
            topics.add("/dm_typing/" + userId);
        }
        var state = accounts.computeIfAbsent(accountId, ignored -> new AccountState());
        if (topics.isEmpty()) {
            state.subscribers.remove(subscriberId);
        } else {
            state.subscribers.put(subscriberId, Set.copyOf(topics));
        }
        reconnectIfChanged(accountId, state);
        return status(accountId, state);
    }

    public synchronized void remove(String accountId, String subscriberId) {
        validateSubscriberId(subscriberId);
        var state = accounts.get(accountId);
        if (state == null) {
            return;
        }
        state.subscribers.remove(subscriberId);
        reconnectIfChanged(accountId, state);
        if (state.subscribers.isEmpty()) {
            accounts.remove(accountId);
        }
    }

    @EventListener
    public synchronized void closeAll(VaultLockedEvent ignored) {
        for (var state : accounts.values()) {
            close(state.connection);
        }
        accounts.clear();
    }

    private void reconnectIfChanged(String accountId, AccountState state) {
        var mergedTopics = new HashSet<String>();
        for (var subscriberTopics : state.subscribers.values()) {
            mergedTopics.addAll(subscriberTopics);
        }
        var immutableTopics = Set.copyOf(mergedTopics);
        if (immutableTopics.equals(state.connectedTopics)) {
            return;
        }
        close(state.connection);
        state.connection = null;
        state.connectedTopics = immutableTopics;
        state.lastError = null;
        if (immutableTopics.isEmpty()) {
            return;
        }
        state.connection = connector.open(
                accountId,
                immutableTopics,
                body -> onEvent(accountId, body),
                error -> recordError(accountId, error));
    }

    private void onEvent(String accountId, String body) {
        var event = eventParser.parse(body);
        timelineEventBus.publish(accountId, "live:" + event.type(), event.entityId());
    }

    private void recordError(String accountId, Throwable error) {
        synchronized (this) {
            var state = accounts.get(accountId);
            if (state != null) {
                state.lastError = error.getClass().getSimpleName();
                state.lastEventAt = Instant.now();
            }
        }
        timelineEventBus.publish(accountId, "live:error", null);
    }

    private static SubscriptionStatus status(String accountId, AccountState state) {
        return new SubscriptionStatus(
                accountId,
                state.connection != null,
                state.connectedTopics.size(),
                state.lastError,
                state.lastEventAt);
    }

    private static void validateSubscriberId(String subscriberId) {
        if (subscriberId == null || !subscriberId.matches("[A-Za-z0-9._:-]{1,200}")) {
            throw new IllegalArgumentException("Live Pipelineの購読ID形式が不正です。");
        }
    }

    private static void close(LivePipelineConnector.Connection connection) {
        if (connection != null) {
            connection.close();
        }
    }

    private static final class AccountState {
        private final Map<String, Set<String>> subscribers = new HashMap<>();
        private Set<String> connectedTopics = Set.of();
        private LivePipelineConnector.Connection connection;
        private String lastError;
        private Instant lastEventAt;
    }

    public record SubscriptionStatus(
            String accountId,
            boolean connected,
            int topicCount,
            String lastError,
            Instant lastEventAt) {}
}
