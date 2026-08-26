package dev.nytweetdeck.timeline;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class TimelineEventBus {

    private final Map<String, CopyOnWriteArrayList<Consumer<TimelineEvent>>> listeners =
            new ConcurrentHashMap<>();

    public AutoCloseable subscribe(String accountId, Consumer<TimelineEvent> listener) {
        validateAccountId(accountId);
        var accountListeners = listeners.computeIfAbsent(accountId, ignored -> new CopyOnWriteArrayList<>());
        accountListeners.add(listener);
        return () -> remove(accountId, listener);
    }

    public void publish(String accountId, String reason, String postId) {
        validateAccountId(accountId);
        var event = new TimelineEvent(UUID.randomUUID().toString(), reason, postId, Instant.now());
        for (var listener : listeners.getOrDefault(accountId, new CopyOnWriteArrayList<>())) {
            listener.accept(event);
        }
    }

    private void remove(String accountId, Consumer<TimelineEvent> listener) {
        var accountListeners = listeners.get(accountId);
        if (accountListeners == null) {
            return;
        }
        accountListeners.remove(listener);
        if (accountListeners.isEmpty()) {
            listeners.remove(accountId, accountListeners);
        }
    }

    private static void validateAccountId(String accountId) {
        if (accountId == null || accountId.isBlank() || accountId.length() > 200) {
            throw new IllegalArgumentException("アカウントIDの形式が不正です。");
        }
    }

    public record TimelineEvent(String id, String reason, String postId, Instant occurredAt) {}
}
