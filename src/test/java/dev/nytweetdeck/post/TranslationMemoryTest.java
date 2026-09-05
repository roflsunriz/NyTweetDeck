package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TranslationMemoryTest {
    private TranslationMemory.Key key(String id) {
        return new TranslationMemory.Key("account", "note", id, "en", "ja", "original");
    }

    @Test
    void keepsOnlySuccessAndEvictsLeastRecentlyUsed() {
        var memory = new TranslationMemory<String>(2);
        memory.load(key("1"), () -> "one", value -> true);
        memory.load(key("2"), () -> "two", value -> true);
        assertThat(memory.load(key("1"), () -> "wrong", value -> true)).isEqualTo("one");
        memory.load(key("3"), () -> "three", value -> true);
        assertThat(memory.load(key("2"), () -> "new two", value -> true)).isEqualTo("new two");
        memory.load(key("missing"), () -> "unavailable", value -> false);
        assertThat(memory.load(key("missing"), () -> "available", value -> true)).isEqualTo("available");
        assertThatThrownBy(() -> memory.load(key("failed"), () -> { throw new IllegalStateException("failure"); }, value -> true))
                .hasMessage("failure");
        assertThat(memory.load(key("failed"), () -> "recovered", value -> true)).isEqualTo("recovered");
        assertThat(memory.size()).isEqualTo(2);
    }

    @Test
    void sharesPendingWithoutHoldingTheLockDuringUpstreamWork() throws Exception {
        var memory = new TranslationMemory<String>(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var joined = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var first = CompletableFuture.supplyAsync(() -> memory.load(key("1"), () -> {
            calls.incrementAndGet();
            started.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            return "translated";
        }, value -> true));
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> memory.load(key("1"), () -> "wrong", value -> true, () -> {}, joined::countDown));
        try {
            assertThat(joined.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(memory.load(key("2"), () -> "other", value -> true)).isEqualTo("other");
            assertThat(memory.pendingCount()).isEqualTo(1);
        } finally { release.countDown(); }
        assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("translated");
        assertThat(second.get(3, TimeUnit.SECONDS)).isEqualTo("translated");
        assertThat(calls.get()).isEqualTo(1);
    }
}
