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
        var event = new TimelineEvent(
                UUID.randomUUID().toString(),
                reason,
                postId,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null);
        notifyListeners(accountId, event);
    }

    public void publishUserSuppression(String accountId, String reason, String userId) {
        validateAccountId(accountId);
        if (!("mute".equals(reason) || "block".equals(reason))
                || userId == null
                || !userId.matches("[0-9]{1,30}")) {
            throw new IllegalArgumentException("ユーザー除外イベントが不正です。");
        }
        var event = new TimelineEvent(
                UUID.randomUUID().toString(),
                reason,
                null,
                userId,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null);
        notifyListeners(accountId, event);
    }

    public void publishEngagement(
            String accountId, String postId, EngagementCounts counts) {
        validateAccountId(accountId);
        var event = new TimelineEvent(
                UUID.randomUUID().toString(),
                "live:tweet_engagement",
                postId,
                null,
                Instant.now(),
                counts.replyCount(),
                counts.repostCount(),
                counts.quoteCount(),
                counts.likeCount(),
                counts.bookmarkCount(),
                counts.viewCount());
        notifyListeners(accountId, event);
    }

    private void notifyListeners(String accountId, TimelineEvent event) {
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

    public record EngagementCounts(
            Long replyCount,
            Long repostCount,
            Long quoteCount,
            Long likeCount,
            Long bookmarkCount,
            Long viewCount) {}

    public record TimelineEvent(
            String id,
            String reason,
            String postId,
            String userId,
            Instant occurredAt,
            Long replyCount,
            Long repostCount,
            Long quoteCount,
            Long likeCount,
            Long bookmarkCount,
            Long viewCount) {}
}
