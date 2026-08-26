package dev.nytweetdeck.xapi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nytweetdeck.account.vault.AccountSecrets;
import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.account.vault.EncryptedAccountVault;
import dev.nytweetdeck.xapi.credentials.AndroidClientCredentialsProvider;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfile;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfileStore;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile.OperationType;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import dev.nytweetdeck.xapi.profile.AndroidFeatureDefaultsService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
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
    void sendsVersionedQueryWithSelectedFeaturesAndOAuth() throws Exception {
        var rawQuery = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        server.createContext("/graphql/op-id/HomeTimeline", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":{\"timeline\":{}}}");
        });
        var client = createClient();

        var result = client.execute("account-1", "homeForYou", Map.of("count", 20));

        var query = parseQuery(rawQuery.get());
        assertThat(query.get("variables")).contains("\"count\":20");
        assertThat(query.get("features")).isEqualTo("{\"feature_enabled\":true}");
        assertThat(authorization.get())
                .contains("oauth_consumer_key=\"client-key\"")
                .contains("oauth_token=\"oauth-token\"")
                .contains("oauth_signature=")
                .doesNotContain("client-secret", "oauth-secret");
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

    private AuthenticatedGraphQlClient createClient() throws Exception {
        var jsonMapper = JsonMapper.builder().build();
        var operation = new AndroidApiProfile.GraphQlOperation(
                "home_timeline", "op-id", "HomeTimeline", OperationType.QUERY);
        var profile = new AndroidApiProfile(
                "com.twitter.android",
                "12.19.1-release.0",
                312191000,
                baseUri,
                baseUri.resolve("/graphql"),
                Map.of("X-Twitter-Client", "TwitterAndroid"),
                Map.of(),
                java.util.List.of("feature_enabled"),
                Map.of("homeForYou", operation));
        var profileService = new AndroidApiProfileService(jsonMapper) {
            @Override
            public AndroidApiProfile profile() {
                return profile;
            }

            @Override
            public AndroidApiProfile.GraphQlOperation requireOperation(String purpose) {
                return profile.graphqlOperations().get(purpose);
            }
        };
        var featureService = new AndroidFeatureDefaultsService(jsonMapper) {
            @Override
            public Map<String, Boolean> selectFor(AndroidApiProfile ignored) {
                return Map.of("feature_enabled", true);
            }
        };
        var credentialPath = temporaryDirectory.resolve("client.properties");
        Files.writeString(
                credentialPath, "consumerKey=client-key\nconsumerSecret=client-secret\n");
        var credentialsProvider = new AndroidClientCredentialsProvider(credentialPath.toString());
        var deviceStore = new AndroidDeviceProfileStore(
                jsonMapper, temporaryDirectory.resolve("device.json").toString());
        deviceStore.save(AndroidDeviceProfile.create(
                "Pixel Test", "16", "Google", "google", "product", "2026-08-05", "ja"));
        var passphrase = "correct horse battery staple".toCharArray();
        var vault = new EncryptedAccountVault(
                jsonMapper,
                temporaryDirectory.resolve("accounts.vault").toString());
        var vaultSession = new AccountVaultSessionManager(vault);
        vaultSession.create(passphrase);
        vaultSession.addOrReplace(new AccountSecrets(
                "account-1", "42", "alice", "Alice", "oauth-token", "oauth-secret"));
        Arrays.fill(passphrase, '\0');
        return new AuthenticatedGraphQlClient(
                HttpClient.newHttpClient(),
                jsonMapper,
                profileService,
                featureService,
                credentialsProvider,
                deviceStore,
                vaultSession,
                new AndroidRequestHeaders(),
                new OAuth1Signer(),
                new SecureRandom(new byte[] {9, 10, 11, 12}));
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
