package dev.nytweetdeck.post;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient.RateLimitInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PostTranslationService {

    private static final int MAX_CACHE_ENTRIES = 1_000;
    private static final int RECENT_OUTCOME_LIMIT = 100;
    private static final double RATE_LIMIT_RESERVE_RATIO = 0.05;
    private static final long MAX_RATE_LIMIT_DELAY_SECONDS = 3_600;
    private static final String LANGUAGE_PATTERN = "[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*";
    private static final String TRANSLATION_SOURCE = "X";

    private final AuthenticatedRestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<CacheKey, TranslationResult> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, TranslationResult> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private final Map<CacheKey, CompletableFuture<TranslationResult>> inFlight =
            new ConcurrentHashMap<>();
    private final Map<String, RateLimitState> accountRateLimits = new ConcurrentHashMap<>();
    private final AtomicReference<RateLimitState> latestRateLimitState = new AtomicReference<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong joinedRequests = new AtomicLong();
    private final AtomicLong deferredRequests = new AtomicLong();
    private final AtomicLong upstreamRequests = new AtomicLong();
    private final AtomicLong upstreamSuccesses = new AtomicLong();
    private final AtomicLong rateLimitedResponses = new AtomicLong();
    private final AtomicLong upstreamServerErrors = new AtomicLong();
    private final AtomicLong transportErrors = new AtomicLong();
    private final ArrayDeque<Boolean> recentOutcomes = new ArrayDeque<>();

    @Autowired
    public PostTranslationService(
            AuthenticatedRestClient restClient,
            ObjectMapper objectMapper) {
        this(restClient, objectMapper, Clock.systemUTC());
    }

    PostTranslationService(
            AuthenticatedRestClient restClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public TranslationResult translate(
            String accountId, String postId, String sourceLanguage, String targetLanguage) {
        requests.incrementAndGet();
        PostService.validatePostId(postId);
        var source = normalizeLanguage(sourceLanguage, "元言語");
        var target = normalizeLanguage(targetLanguage, "翻訳先言語");
        if (baseLanguage(source).equals(baseLanguage(target))) {
            throw new IllegalArgumentException("元言語と翻訳先言語が同じです。");
        }
        var key = new CacheKey(accountId, postId, source, target);
        var cached = cache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        var pending = new CompletableFuture<TranslationResult>();
        var existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) {
            joinedRequests.incrementAndGet();
            return join(existing);
        }
        try {
            ensureRateLimitAvailable(accountId);
            upstreamRequests.incrementAndGet();
            try {
                var response = restClient.get(
                        accountId,
                        "translatePost",
                        Map.of("postId", postId, "translationSource", TRANSLATION_SOURCE),
                        Map.of(),
                        target);
                updateRateLimit(accountId, response.rateLimit());
                var translated = parse(response.rawJson(), postId, source, target);
                cache.put(key, translated);
                upstreamSuccesses.incrementAndGet();
                recordRecentOutcome(true);
                pending.complete(translated);
                return translated;
            } catch (RuntimeException exception) {
                recordUpstreamFailure(accountId, exception);
                pending.completeExceptionally(exception);
                throw exception;
            }
        } catch (RuntimeException exception) {
            pending.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, pending);
        }
    }

    public TranslationHealth health() {
        var upstream = upstreamRequests.get();
        var successes = upstreamSuccesses.get();
        var rateLimit = latestRateLimitState.get();
        int cacheSize;
        synchronized (cache) {
            cacheSize = cache.size();
        }
        return new TranslationHealth(
                requests.get(),
                upstream,
                successes,
                percentage(successes, upstream),
                recentSuccessRate(),
                cacheHits.get(),
                joinedRequests.get(),
                deferredRequests.get(),
                rateLimitedResponses.get(),
                upstreamServerErrors.get(),
                transportErrors.get(),
                rateLimit == null ? null : rateLimit.limit(),
                rateLimit == null ? null : rateLimit.remaining(),
                rateLimit == null ? null : rateLimit.resetAt(),
                cacheSize,
                inFlight.size());
    }

    TranslationResult parse(
            String body,
            String postId,
            String sourceLanguage,
            String targetLanguage) {
        try {
            var root = objectMapper.readTree(body);
            var responsePostId = text(root, "id_str");
            if (responsePostId != null && !postId.equals(responsePostId)) {
                throw new XApiHttpException("翻訳応答のポストIDが一致しません。", 502);
            }
            var translation = text(root, "translation");
            if (translation == null || translation.isBlank()) {
                throw new XApiHttpException("X翻訳応答に翻訳文がありません。", 502);
            }
            return new TranslationResult(
                    postId, sourceLanguage, targetLanguage, translation, TRANSLATION_SOURCE);
        } catch (JacksonException exception) {
            throw new XApiHttpException("X翻訳応答を解析できません。", exception);
        }
    }

    private static String normalizeLanguage(String value, String label) {
        var normalized = value == null ? "" : value.strip().replace('_', '-');
        if (!normalized.matches(LANGUAGE_PATTERN)) {
            throw new IllegalArgumentException(label + "の形式が不正です。");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String baseLanguage(String language) {
        var separator = language.indexOf('-');
        return separator < 0 ? language : language.substring(0, separator);
    }

    private void ensureRateLimitAvailable(String accountId) {
        var state = accountRateLimits.get(accountId);
        var now = clock.instant();
        if (state == null || state.blockedUntil() == null || !state.blockedUntil().isAfter(now)) {
            return;
        }
        deferredRequests.incrementAndGet();
        var retryAfter = Math.max(1, Duration.between(now, state.blockedUntil()).toSeconds() + 1);
        throw new XApiHttpException("X翻訳の利用枠リセットを待っています。", 429, retryAfter);
    }

    private void updateRateLimit(String accountId, RateLimitInfo rateLimit) {
        if (rateLimit == null
                || rateLimit.limit() == null
                || rateLimit.remaining() == null
                || rateLimit.resetAt() == null) {
            return;
        }
        var reserve = Math.max(1, (int) Math.ceil(rateLimit.limit() * RATE_LIMIT_RESERVE_RATIO));
        var blockedUntil = rateLimit.remaining() <= reserve ? rateLimit.resetAt() : null;
        var state = new RateLimitState(
                rateLimit.limit(), rateLimit.remaining(), rateLimit.resetAt(), blockedUntil);
        accountRateLimits.put(accountId, state);
        latestRateLimitState.set(state);
    }

    private void recordUpstreamFailure(String accountId, RuntimeException exception) {
        recordRecentOutcome(false);
        if (exception instanceof XApiHttpException xApiException) {
            if (xApiException.statusCode() == 429) {
                rateLimitedResponses.incrementAndGet();
                var delay = xApiException.retryAfterSeconds();
                if (delay != null) {
                    var boundedDelay = Math.max(1, Math.min(MAX_RATE_LIMIT_DELAY_SECONDS, delay));
                    var blockedUntil = clock.instant().plusSeconds(boundedDelay);
                    var current = accountRateLimits.get(accountId);
                    var state = new RateLimitState(
                            current == null ? null : current.limit(),
                            0,
                            blockedUntil,
                            blockedUntil);
                    accountRateLimits.put(accountId, state);
                    latestRateLimitState.set(state);
                }
            } else if (xApiException.statusCode() >= 500) {
                upstreamServerErrors.incrementAndGet();
            } else if (xApiException.statusCode() == 0) {
                transportErrors.incrementAndGet();
            }
        }
    }

    private static TranslationResult join(CompletableFuture<TranslationResult> pending) {
        try {
            return pending.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private synchronized void recordRecentOutcome(boolean successful) {
        recentOutcomes.addLast(successful);
        while (recentOutcomes.size() > RECENT_OUTCOME_LIMIT) {
            recentOutcomes.removeFirst();
        }
    }

    private synchronized Double recentSuccessRate() {
        if (recentOutcomes.isEmpty()) {
            return null;
        }
        var successful = recentOutcomes.stream().filter(Boolean::booleanValue).count();
        return percentage(successful, recentOutcomes.size());
    }

    private static Double percentage(long successful, long total) {
        return total == 0 ? null : Math.round(successful * 1_000.0 / total) / 10.0;
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private record CacheKey(
            String accountId,
            String postId,
            String sourceLanguage,
            String targetLanguage) {}

    private record RateLimitState(
            Integer limit,
            Integer remaining,
            Instant resetAt,
            Instant blockedUntil) {}

    public record TranslationHealth(
            long requests,
            long upstreamRequests,
            long upstreamSuccesses,
            Double upstreamSuccessRate,
            Double recentSuccessRate,
            long cacheHits,
            long joinedRequests,
            long deferredRequests,
            long rateLimitedResponses,
            long upstreamServerErrors,
            long transportErrors,
            Integer rateLimit,
            Integer rateLimitRemaining,
            Instant rateLimitResetAt,
            int cacheEntries,
            int inFlightRequests) {}

    public record TranslationResult(
            String postId,
            String sourceLanguage,
            String targetLanguage,
            String text,
            String provider) {}
}
