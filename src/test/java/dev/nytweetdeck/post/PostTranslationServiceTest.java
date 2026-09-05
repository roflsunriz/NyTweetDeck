package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient.RateLimitInfo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostTranslationServiceTest {

    @Test
    void translatesThroughTheNativeXLiveEndpointAndCachesTheResult() {
        var requestedPostId = new AtomicReference<String>();
        var requestedLanguage = new AtomicReference<String>();
        var calls = new int[1];
        var restClient = new AuthenticatedRestClient(null, null, null) {
            @Override
            public RestResult postJson(
                    String accountId,
                    String endpointName,
                    String body,
                    String language) {
                calls[0]++;
                assertThat(endpointName).isEqualTo("grokTranslation");
                var payload = JsonMapper.builder().build().readTree(body);
                requestedPostId.set(payload.path("id").asString());
                assertThat(payload.path("content_type").asString()).isEqualTo("POST");
                assertThat(payload.path("dst_lang").asString()).isEqualTo("ja");
                requestedLanguage.set(language);
                return new RestResult(
                        endpointName,
                        "{\"result\":{\"content_type\":\"POST\",\"text\":\"こんにちは世界\"}}");
            }
        };
        var mapper = JsonMapper.builder().build();
        var service = new PostTranslationService(restClient, mapper);

        var first = service.translate("account-1", "123", "en", "ja");
        var second = service.translate("account-1", "123", "en", "ja");

        assertThat(first.text()).isEqualTo("こんにちは世界");
        assertThat(first.provider()).isEqualTo("X");
        assertThat(second).isSameAs(first);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(requestedPostId.get()).isEqualTo("123");
        assertThat(requestedLanguage.get()).isEqualTo("ja");
    }

    @Test
    void rejectsEqualOrInvalidLanguagesBeforeCommunication() {
        var service = new PostTranslationService(
                null, JsonMapper.builder().build());

        assertThatThrownBy(() -> service.translate("account-1", "123", "ja-JP", "ja"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同じ");
        assertThatThrownBy(() -> service.translate("account-1", "123", "unknown", "ja"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
    }

    @Test
    void joinsOnlyPostChunksAndRejectsEmptyOrFailedStreams() {
        var service = new PostTranslationService(null, JsonMapper.builder().build());
        var translated = service.parse("""
                {"result":{"content_type":"POST","text":"Hello "}}
                {"result":{"content_type":"POLL","text":"Not post text"}}
                {"result":{"content_type":"POST","text":"world"}}
                """, "123", "en", "ja");
        assertThat(translated.text()).isEqualTo("Hello world");
        for (var body : java.util.List.of("", "{\"result\":{\"content_type\":\"POLL\",\"text\":\"poll\"}}", "{\"error\":\"failed\"}", "{\"result\":")) {
            assertThatThrownBy(() -> service.parse(body, "123", "en", "ja"))
                    .isInstanceOf(dev.nytweetdeck.xapi.http.XApiHttpException.class);
        }
    }

    @Test
    void reservesTheLastFivePercentOfTheObservedXTranslationAllowance() {
        var calls = new int[1];
        var resetAt = Instant.parse("2026-08-27T16:00:00Z");
        var restClient = new AuthenticatedRestClient(null, null, null) {
            @Override
            public RestResult postJson(
                    String accountId,
                    String endpointName,
                    String body,
                    String language) {
                calls[0]++;
                return new RestResult(
                        endpointName,
                        "{\"result\":{\"content_type\":\"POST\",\"text\":\"翻訳\"}}",
                        new RateLimitInfo(100, 5, resetAt));
            }
        };
        var service = new PostTranslationService(
                restClient,
                JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-08-27T15:50:00Z"), ZoneOffset.UTC));

        service.translate("account-1", "123", "en", "ja");

        assertThatThrownBy(() -> service.translate("account-1", "124", "en", "ja"))
                .isInstanceOf(dev.nytweetdeck.xapi.http.XApiHttpException.class)
                .satisfies(exception -> {
                    var xApiException =
                            (dev.nytweetdeck.xapi.http.XApiHttpException) exception;
                    assertThat(xApiException.statusCode()).isEqualTo(429);
                    assertThat(xApiException.retryAfterSeconds()).isEqualTo(601);
                });
        assertThat(calls[0]).isEqualTo(1);
        assertThat(service.health().upstreamSuccessRate()).isEqualTo(100.0);
        assertThat(service.health().deferredRequests()).isEqualTo(1);
        assertThat(service.health().rateLimitRemaining()).isEqualTo(5);
    }

    @Test
    void joinsConcurrentRequestsForTheSamePostIntoOneXCall() throws Exception {
        var calls = new int[1];
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var restClient = new AuthenticatedRestClient(null, null, null) {
            @Override
            public RestResult postJson(
                    String accountId,
                    String endpointName,
                    String body,
                    String language) {
                calls[0]++;
                started.countDown();
                try {
                    if (!release.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("同時要求テストがタイムアウトしました。");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new RestResult(
                        endpointName,
                        "{\"result\":{\"content_type\":\"POST\",\"text\":\"翻訳\"}}");
            }
        };
        var service = new PostTranslationService(restClient, JsonMapper.builder().build());

        var first = CompletableFuture.supplyAsync(
                () -> service.translate("account-1", "123", "en", "ja"));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(
                () -> service.translate("account-1", "123", "en", "ja"));
        for (var attempt = 0; attempt < 100 && service.health().joinedRequests() == 0; attempt++) {
            Thread.sleep(5);
        }
        assertThat(service.health().joinedRequests()).isEqualTo(1);
        release.countDown();

        assertThat(first.get(1, TimeUnit.SECONDS).text()).isEqualTo("翻訳");
        assertThat(second.get(1, TimeUnit.SECONDS).text()).isEqualTo("翻訳");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(service.health().upstreamRequests()).isEqualTo(1);
    }
}
