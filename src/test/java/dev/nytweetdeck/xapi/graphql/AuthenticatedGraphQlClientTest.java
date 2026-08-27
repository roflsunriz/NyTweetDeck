package dev.nytweetdeck.xapi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nytweetdeck.account.AccountSecrets;
import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.http.XClientTransactionIdService;
import dev.nytweetdeck.xapi.profile.XApiProfile;
import dev.nytweetdeck.xapi.profile.XApiProfile.OperationType;
import dev.nytweetdeck.xapi.profile.XApiProfileService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AuthenticatedGraphQlClientTest {

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsVersionedQueryWithSelectedFeaturesAndWebSession() throws Exception {
        var rawQuery = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var cookie = new AtomicReference<String>();
        var csrfToken = new AtomicReference<String>();
        server.createContext("/graphql/op-id/HomeTimeline", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            csrfToken.set(exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
            respond(exchange, 200, "{\"data\":{\"timeline\":{}}}");
        });
        var client = createClient();

        var result = client.execute("account-1", "homeForYou", Map.of("count", 20));

        var query = parseQuery(rawQuery.get());
        assertThat(query.get("variables")).contains("\"count\":20");
        assertThat(query.get("features")).isEqualTo("{\"feature_enabled\":true}");
        assertThat(query.get("fieldToggles"))
                .contains("\"withArticlePlainText\":false", "\"withPayments\":false");
        assertThat(authorization.get()).isEqualTo("Bearer web-bearer");
        assertThat(cookie.get()).contains("auth_token=web-auth", "ct0=web-csrf");
        assertThat(csrfToken.get()).isEqualTo("web-csrf");
        assertThat(result.rawJson()).contains("timeline");
        assertThat(result.toString()).doesNotContain("timeline");
    }

    @Test
    void rejectsGraphQlErrorWithoutReturningRawDetails() throws Exception {
        server.createContext("/graphql/op-id/HomeTimeline", exchange ->
                respond(exchange, 200, "{\"errors\":[{\"message\":\"private detail\"}]}"));
        var client = createClient();

        assertThatThrownBy(() -> client.execute("account-1", "homeForYou", Map.of()))
                .isInstanceOf(XApiHttpException.class)
                .hasMessageContaining("エラーを返しました")
                .hasMessageNotContaining("private detail");
    }

    @Test
    void acceptsPartialGraphQlDataWhenNonFatalErrorsAreAlsoPresent() throws Exception {
        server.createContext("/graphql/op-id/HomeTimeline", exchange -> respond(
                exchange,
                200,
                "{\"data\":{\"timeline\":{}},\"errors\":[{\"message\":\"one item unavailable\"}]}"));
        var client = createClient();

        var result = client.execute("account-1", "homeForYou", Map.of());

        assertThat(result.rawJson()).contains("timeline");
    }

    @Test
    void sendsSearchTimelineAsPostWithJsonPayload() throws Exception {
        var client = createClient("search", "SearchTimeline");

        var request = client
                .prepareRequest(
                        "account-1",
                        "search",
                        Map.of("rawQuery", "NyTweetDeck", "count", 20))
                .request();

        var payload = JsonMapper.builder().build().readTree(readBody(request));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getRawQuery()).isNull();
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(payload.get("variables").get("rawQuery").asString()).isEqualTo("NyTweetDeck");
        assertThat(payload.get("features").get("feature_enabled").asBoolean()).isTrue();
        assertThat(payload.get("fieldToggles").get("withArticlePlainText").asBoolean()).isFalse();
    }

    @Test
    void sendsTheNyTweetDeckUiLanguageToXWeb() throws Exception {
        var client = createClient();

        var request = client.prepareRequest(
                        "account-1", "homeForYou", Map.of("count", 20), "fr-CA")
                .request();

        assertThat(request.headers().firstValue("X-Twitter-Client-Language"))
                .contains("fr-ca");
        assertThatThrownBy(() -> client.prepareRequest(
                        "account-1", "homeForYou", Map.of(), "ja\r\nInjected: yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表示言語");
    }

    @Test
    void includesTheResolvedQueryIdInMutationPayloads() throws Exception {
        var client = createClient("bookmark", "CreateBookmark", OperationType.MUTATION);

        var request = client
                .prepareRequest("account-1", "bookmark", Map.of("tweet_id", "123"))
                .request();
        var payload = JsonMapper.builder().build().readTree(readBody(request));

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("X-Client-Transaction-Id"))
                .contains("test-transaction-id");
        assertThat(payload.get("queryId").asString()).isEqualTo("op-id");
        assertThat(payload.get("variables").get("tweet_id").asString()).isEqualTo("123");
        assertThat(payload.get("features")).isNull();
    }

    @Test
    void refreshesTheWebTransactionMaterialOnceWhenXMisreportsMutationCode344()
            throws Exception {
        var requests = new AtomicInteger();
        server.createContext("/graphql/op-id/CreateRetweet", exchange -> {
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 200, "{\"errors\":[{\"code\":344}]}");
            } else {
                respond(exchange, 200, "{\"data\":{\"create_retweet\":{}}}");
            }
        });
        var transactions = new RecordingTransactionService();
        var client = createClient(
                "repost", "CreateRetweet", OperationType.MUTATION, transactions);

        var result = client.execute("account-1", "repost", Map.of("tweet_id", "123"));

        assertThat(result.rawJson()).contains("create_retweet");
        assertThat(requests).hasValue(2);
        assertThat(transactions.invalidations).hasValue(1);
        assertThat(transactions.generations).hasValue(2);
    }

    private AuthenticatedGraphQlClient createClient() throws Exception {
        return createClient("homeForYou", "HomeTimeline");
    }

    private AuthenticatedGraphQlClient createClient(String purpose, String operationName)
            throws Exception {
        return createClient(purpose, operationName, OperationType.QUERY);
    }

    private AuthenticatedGraphQlClient createClient(
            String purpose, String operationName, OperationType operationType)
            throws Exception {
        return createClient(
                purpose,
                operationName,
                operationType,
                new XClientTransactionIdService(null) {
                    @Override
                    public String generate(String method, URI requestUri) {
                        return "test-transaction-id";
                    }
                });
    }

    private AuthenticatedGraphQlClient createClient(
            String purpose,
            String operationName,
            OperationType operationType,
            XClientTransactionIdService transactionIdService)
            throws Exception {
        var jsonMapper = JsonMapper.builder().build();
        var operation = new XApiProfile.GraphQlOperation(
                purpose, "op-id", operationName, operationType);
        var profile = new XApiProfile(
                "x-web",
                "current",
                0,
                baseUri,
                baseUri.resolve("/graphql"),
                Map.of("X-Twitter-Client", "TwitterWebClient"),
                Map.of(),
                java.util.List.of("feature_enabled"),
                Map.of(purpose, operation));
        var profileService = new XApiProfileService(jsonMapper) {
            @Override
            public XApiProfile profile() {
                return profile;
            }

            @Override
            public XApiProfile.GraphQlOperation requireOperation(String purpose) {
                return profile.graphqlOperations().get(purpose);
            }

            @Override
            public Map<String, Boolean> selectFeatures(XApiProfile.GraphQlOperation ignored) {
                return Map.of("feature_enabled", true);
            }
        };
        var accountStore = new AccountStore(
                jsonMapper, temporaryDirectory.resolve("accounts.json"));
        accountStore.addOrReplace(AccountSecrets.webSession(
                "account-1", "42", "alice", "Alice", "web-bearer", "web-auth", "web-csrf"));
        return new AuthenticatedGraphQlClient(
                HttpClient.newHttpClient(),
                jsonMapper,
                profileService,
                accountStore,
                transactionIdService);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        var pairs = new java.util.LinkedHashMap<String, String>();
        for (String part : rawQuery.split("&")) {
            var separator = part.indexOf('=');
            pairs.put(
                    URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return pairs;
    }

    private static String readBody(HttpRequest request) {
        var publisher = request.bodyPublisher().orElseThrow();
        var subscriber = new BodySubscriber();
        publisher.subscribe(subscriber);
        return subscriber.body().join();
    }

    private static final class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<String> body = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            var bytes = new byte[item.remaining()];
            item.get(bytes);
            output.writeBytes(bytes);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toString(StandardCharsets.UTF_8));
        }

        CompletableFuture<String> body() {
            return body;
        }
    }

    private static final class RecordingTransactionService
            extends XClientTransactionIdService {
        private final AtomicInteger generations = new AtomicInteger();
        private final AtomicInteger invalidations = new AtomicInteger();

        private RecordingTransactionService() {
            super(null);
        }

        @Override
        public String generate(String method, URI requestUri) {
            return "test-transaction-id-" + generations.incrementAndGet();
        }

        @Override
        public synchronized void invalidate() {
            invalidations.incrementAndGet();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
