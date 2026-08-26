package dev.nytweetdeck.xapi.live;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.xapi.credentials.AndroidClientCredentialsProvider;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfileStore;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer.Credentials;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
    private final AndroidApiProfileService profileService;
    private final AndroidClientCredentialsProvider clientCredentialsProvider;
    private final AndroidDeviceProfileStore deviceProfileStore;
    private final AccountVaultSessionManager vaultSessionManager;
    private final AndroidRequestHeaders androidRequestHeaders;
    private final OAuth1Signer oauth1Signer;
    private final SecureRandom secureRandom = new SecureRandom();

    public LivePipelineClient(
            HttpClient httpClient,
            AndroidApiProfileService profileService,
            AndroidClientCredentialsProvider clientCredentialsProvider,
            AndroidDeviceProfileStore deviceProfileStore,
            AccountVaultSessionManager vaultSessionManager,
            AndroidRequestHeaders androidRequestHeaders,
            OAuth1Signer oauth1Signer) {
        this.httpClient = httpClient;
        this.profileService = profileService;
        this.clientCredentialsProvider = clientCredentialsProvider;
        this.deviceProfileStore = deviceProfileStore;
        this.vaultSessionManager = vaultSessionManager;
        this.androidRequestHeaders = androidRequestHeaders;
        this.oauth1Signer = oauth1Signer;
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
        var thread = Thread.ofVirtual()
                .name("nytweetdeck-live-pipeline-" + accountId)
                .start(() -> run(
                        accountId,
                        topics,
                        firstRequest,
                        eventConsumer,
                        errorConsumer,
                        closed,
                        currentStream));
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
        var uri = URI.create(profile.restBaseUri().resolve(endpoint)
                + "?topic="
                + URLEncoder.encode(topicValue, StandardCharsets.UTF_8).replace("+", "%20"));
        var account = vaultSessionManager.requireAccount(accountId);
        var clientCredentials = clientCredentialsProvider.require();
        var oauthCredentials = new Credentials(
                clientCredentials.consumerKey(),
                clientCredentials.consumerSecret(),
                account.oauthToken(),
                account.oauthTokenSecret());
        var authorization = oauth1Signer.authorizationHeader(
                "GET",
                uri,
                java.util.List.of(),
                oauthCredentials,
                Long.toString(Instant.now().getEpochSecond()),
                newNonce());
        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(CONNECT_TIMEOUT)
                .header("Authorization", authorization)
                .header("Accept", "text/event-stream")
                .GET();
        androidRequestHeaders.apply(
                requestBuilder, profile, deviceProfileStore.require().toIdentity(profile));
        return requestBuilder.build();
    }

    private String newNonce() {
        var bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
