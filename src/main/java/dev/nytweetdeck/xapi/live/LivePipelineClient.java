package dev.nytweetdeck.xapi.live;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class LivePipelineClient implements LivePipelineConnector {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RECONNECTS = 10;

    private final HttpClient httpClient;
    private final XApiProfileService profileService;
    private final AccountVaultSessionManager vaultSessionManager;

    public LivePipelineClient(
            HttpClient httpClient,
            XApiProfileService profileService,
            AccountVaultSessionManager vaultSessionManager) {
        this.httpClient = httpClient;
        this.profileService = profileService;
        this.vaultSessionManager = vaultSessionManager;
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
        var firstRequest = createRequest(accountId, topics);
        var closed = new AtomicBoolean();
        var currentStream = new AtomicReference<InputStream>();
        var thread = new Thread(() -> run(
                        accountId,
                        topics,
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
            HttpRequest firstRequest,
            Consumer<String> eventConsumer,
            Consumer<Throwable> errorConsumer,
            AtomicBoolean closed,
            AtomicReference<InputStream> currentStream) {
        long retryDelay = 500;
        for (int attempt = 0; attempt <= MAX_RECONNECTS && !closed.get(); attempt++) {
            try {
                var request = attempt == 0 ? firstRequest : createRequest(accountId, topics);
                readStream(request, eventConsumer, closed, currentStream);
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
                close(currentStream.getAndSet(null));
            }
            if (!closed.get() && attempt < MAX_RECONNECTS) {
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
            HttpRequest request,
            Consumer<String> eventConsumer,
            AtomicBoolean closed,
            AtomicReference<InputStream> currentStream)
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
                    eventConsumer.accept(trimmed.substring(5).trim());
                }
            }
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
                + URLEncoder.encode(topicValue, StandardCharsets.UTF_8).replace("+", "%20"));
        var account = vaultSessionManager.requireAccount(accountId);
        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(CONNECT_TIMEOUT)
                .GET();
        WebSessionRequestHeaders.apply(requestBuilder, account, "ja");
        requestBuilder.header("Accept", "text/event-stream");
        return requestBuilder.build();
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
}
