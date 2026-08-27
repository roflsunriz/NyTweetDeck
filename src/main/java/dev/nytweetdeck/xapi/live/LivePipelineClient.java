package dev.nytweetdeck.xapi.live;

import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.http.WebSessionRequestHeaders;
import dev.nytweetdeck.xapi.profile.XApiProfileService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LivePipelineClient implements LivePipelineConnector {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_AUTO_SUBSCRIBE_TOPICS = 20;
    private static final long DEFAULT_SUBSCRIPTION_TTL_MILLISECONDS = 120_000L;
    private static final long SUBSCRIPTION_RENEWAL_MARGIN_MILLISECONDS = 20_000L;

    private final HttpClient httpClient;
    private final XApiProfileService profileService;
    private final AccountStore accountStore;
    private final ObjectMapper objectMapper;

    public LivePipelineClient(
            HttpClient httpClient,
            XApiProfileService profileService,
            AccountStore accountStore,
            ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.profileService = profileService;
        this.accountStore = accountStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public Connection open(
            String accountId,
            Set<String> topics,
            Consumer<String> eventConsumer,
            Consumer<Throwable> errorConsumer) {
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("Live Pipelineの購読topicが空です。");
        }
        var autoSubscribeTopics = topics.stream()
                .sorted()
                .limit(MAX_AUTO_SUBSCRIBE_TOPICS)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var firstRequest = createRequest(accountId, autoSubscribeTopics);
        var closed = new AtomicBoolean();
        var currentStream = new AtomicReference<InputStream>();
        var thread = new Thread(() -> run(
                        accountId,
                        topics,
                        Set.copyOf(autoSubscribeTopics),
                        firstRequest,
                        eventConsumer,
                        errorConsumer,
                        closed,
                        currentStream),
                "nytweetdeck-live-pipeline-" + accountId);
        thread.setDaemon(true);
        thread.start();
        return () -> {
            closed.set(true);
            close(currentStream.getAndSet(null));
            thread.interrupt();
        };
    }

    private void run(
            String accountId,
            Set<String> topics,
            Set<String> autoSubscribeTopics,
            HttpRequest firstRequest,
            Consumer<String> eventConsumer,
            Consumer<Throwable> errorConsumer,
            AtomicBoolean closed,
            AtomicReference<InputStream> currentStream) {
        long retryDelay = 500;
        var firstAttempt = true;
        var renewalThread = new AtomicReference<Thread>();
        while (!closed.get()) {
            try {
                var request = firstAttempt
                        ? firstRequest
                        : createRequest(accountId, autoSubscribeTopics);
                firstAttempt = false;
                readStream(
                        accountId,
                        topics,
                        autoSubscribeTopics,
                        request,
                        eventConsumer,
                        errorConsumer,
                        closed,
                        currentStream,
                        renewalThread);
                retryDelay = 500;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException | IOException exception) {
                if (closed.get()) {
                    return;
                }
                errorConsumer.accept(exception);
                if (exception instanceof XApiHttpException httpException
                        && httpException.statusCode() >= 400
                        && httpException.statusCode() < 500) {
                    return;
                }
            } finally {
                interrupt(renewalThread.getAndSet(null));
                close(currentStream.getAndSet(null));
            }
            if (!closed.get()) {
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                retryDelay = Math.min(16_000, retryDelay * 2);
            }
        }
    }

    private void readStream(
            String accountId,
            Set<String> topics,
            Set<String> autoSubscribeTopics,
            HttpRequest request,
            Consumer<String> eventConsumer,
            Consumer<Throwable> errorConsumer,
            AtomicBoolean closed,
            AtomicReference<InputStream> currentStream,
            AtomicReference<Thread> renewalThread)
            throws IOException, InterruptedException {
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            close(response.body());
            throw new XApiHttpException(
                    "Live Pipeline接続に失敗しました。HTTP " + response.statusCode(),
                    response.statusCode());
        }
        currentStream.set(response.body());
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                var trimmed = line.trim();
                if (trimmed.regionMatches(true, 0, "data:", 0, 5) && trimmed.length() > 5) {
                    var body = trimmed.substring(5).trim();
                    var config = parseSessionConfig(body);
                    if (config != null) {
                        subscribeRemainingTopics(
                                accountId,
                                topics,
                                autoSubscribeTopics,
                                config,
                                errorConsumer,
                                closed,
                                currentStream,
                                renewalThread);
                    }
                    eventConsumer.accept(body);
                }
            }
        }
    }

    private void subscribeRemainingTopics(
            String accountId,
            Set<String> topics,
            Set<String> autoSubscribeTopics,
            SessionConfig config,
            Consumer<Throwable> errorConsumer,
            AtomicBoolean closed,
            AtomicReference<InputStream> currentStream,
            AtomicReference<Thread> renewalThread) {
        var remainingTopics = new LinkedHashSet<>(topics);
        remainingTopics.removeAll(autoSubscribeTopics);
        if (!remainingTopics.isEmpty()) {
            updateSubscriptions(accountId, config.sessionId(), remainingTopics);
        }
        var renewalDelay = Math.max(
                1_000L,
                config.subscriptionTtlMilliseconds()
                        - SUBSCRIPTION_RENEWAL_MARGIN_MILLISECONDS);
        interrupt(renewalThread.getAndSet(null));
        var thread = new Thread(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(renewalDelay);
                    if (!closed.get()) {
                        updateSubscriptions(accountId, config.sessionId(), topics);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException exception) {
                    if (!closed.get()) {
                        errorConsumer.accept(exception);
                        close(currentStream.getAndSet(null));
                    }
                    return;
                }
            }
        }, "nytweetdeck-live-pipeline-renewal-" + accountId);
        thread.setDaemon(true);
        renewalThread.set(thread);
        thread.start();
    }

    SessionConfig parseSessionConfig(String body) {
        try {
            var root = objectMapper.readTree(body);
            if (!"/system/config".equals(root.path("topic").asString())) {
                return null;
            }
            var config = root.path("payload").path("config");
            var sessionId = config.path("session_id").asString(null);
            if (sessionId == null || sessionId.isBlank() || sessionId.length() > 500) {
                throw new IllegalArgumentException("Live PipelineのセッションIDが不正です。");
            }
            var ttl = config.path("subscription_ttl_millis")
                    .asLong(DEFAULT_SUBSCRIPTION_TTL_MILLISECONDS);
            if (ttl < 1_000L || ttl > Duration.ofDays(1).toMillis()) {
                ttl = DEFAULT_SUBSCRIPTION_TTL_MILLISECONDS;
            }
            return new SessionConfig(sessionId, ttl);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("Live Pipelineの接続設定を解析できません。", exception);
        }
    }

    void updateSubscriptions(String accountId, String sessionId, Set<String> topics) {
        var profile = profileService.profile();
        var endpoint = profile.restEndpoints().get("livePipelineUpdateSubscriptions");
        if (endpoint == null) {
            throw new IllegalStateException("livePipelineUpdateSubscriptionsエンドポイントが未定義です。");
        }
        var topicValue = String.join(",", topics.stream().sorted().toList());
        var form = "sub_topics=" + encode(topicValue) + "&unsub_topics=";
        var uri = URI.create("https://api.x.com").resolve(endpoint);
        var account = accountStore.requireAccount(accountId);
        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(CONNECT_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("LivePipeline-Session", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(form));
        WebSessionRequestHeaders.apply(requestBuilder, account, "ja");
        try {
            var response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(
                        "Live Pipeline購読更新に失敗しました。HTTP " + response.statusCode(),
                        response.statusCode());
            }
        } catch (IOException exception) {
            throw new XApiHttpException("Live Pipeline購読更新の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException("Live Pipeline購読更新が中断されました。", exception);
        }
    }

    HttpRequest createRequest(String accountId, Set<String> topics) {
        var profile = profileService.profile();
        var endpoint = profile.restEndpoints().get("livePipelineEvents");
        if (endpoint == null) {
            throw new IllegalStateException("livePipelineEventsエンドポイントが未定義です。");
        }
        var topicValue = String.join(",", topics.stream().sorted().toList());
        var uri = URI.create(URI.create("https://api.x.com").resolve(endpoint)
                + "?topic="
                + encode(topicValue));
        var account = accountStore.requireAccount(accountId);
        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(CONNECT_TIMEOUT)
                .GET();
        WebSessionRequestHeaders.apply(requestBuilder, account, "ja");
        requestBuilder.setHeader("Accept", "text/event-stream");
        return requestBuilder.build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void close(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing an already failed stream requires no recovery.
        }
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    record SessionConfig(String sessionId, long subscriptionTtlMilliseconds) {}
}
