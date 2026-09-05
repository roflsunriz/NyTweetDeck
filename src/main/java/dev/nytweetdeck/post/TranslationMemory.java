package dev.nytweetdeck.post;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bounded successful results and unbounded-by-LRU pending requests, scoped to this process. */
public final class TranslationMemory<T> {
    private final int capacity;
    private final Map<Key, T> values = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<Key, CompletableFuture<T>> pending = new HashMap<>();

    public TranslationMemory(int capacity) {
        this.capacity = capacity;
    }

    public T load(Key key, Supplier<T> operation, Predicate<T> successful) {
        return load(key, operation, successful, () -> {}, () -> {});
    }

    public T load(Key key, Supplier<T> operation, Predicate<T> successful,
            Runnable onCacheHit, Runnable onJoined) {
        CompletableFuture<T> request;
        boolean owner;
        synchronized (this) {
            var cached = values.get(key);
            if (cached != null) {
                onCacheHit.run();
                return cached;
            }
            request = pending.get(key);
            owner = request == null;
            if (owner) {
                request = new CompletableFuture<>();
                pending.put(key, request);
            }
        }
        if (!owner) {
            onJoined.run();
            try {
                return request.join();
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                throw exception;
            }
        }
        try {
            var value = operation.get();
            synchronized (this) {
                if (successful.test(value)) {
                    values.put(key, value);
                    if (values.size() > capacity) values.remove(values.keySet().iterator().next());
                }
            }
            request.complete(value);
            return value;
        } catch (RuntimeException | Error failure) {
            request.completeExceptionally(failure);
            throw failure;
        } finally {
            synchronized (this) { pending.remove(key, request); }
        }
    }

    public synchronized int size() { return values.size(); }
    public synchronized int pendingCount() { return pending.size(); }

    public record Key(String accountId, String kind, String id, String sourceLanguage,
            String targetLanguage, String text) {}
}
