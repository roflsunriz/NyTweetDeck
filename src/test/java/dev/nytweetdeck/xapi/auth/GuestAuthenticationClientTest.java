package dev.nytweetdeck.xapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nytweetdeck.xapi.credentials.AndroidClientCredentials;
import dev.nytweetdeck.xapi.http.AndroidDeviceIdentity;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GuestAuthenticationClientTest {

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
    void obtainsBearerAndGuestTokensWithAndroidHeaders() {
        var bearerAuthorization = new AtomicReference<String>();
        var guestAuthorization = new AtomicReference<String>();
        var guestClientVersion = new AtomicReference<String>();
        server.createContext("/oauth2/token", safeHandler(exchange -> {
            bearerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"token_type\":\"bearer\",\"access_token\":\"app-token\"}");
        }));
        server.createContext("/1.1/guest/activate.json", safeHandler(exchange -> {
            guestAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            guestClientVersion.set(exchange.getRequestHeaders().getFirst("X-Twitter-Client-Version"));
            respond(exchange, 200, "{\"guest_token\":\"guest-token\"}");
        }));
        var client = createClient();

        var session = client.activate(deviceIdentity());

        assertThat(session.bearerToken()).isEqualTo("app-token");
        assertThat(session.guestToken()).isEqualTo("guest-token");
        assertThat(session.toString()).doesNotContain("app-token", "guest-token");
        assertThat(bearerAuthorization.get()).isEqualTo("Basic Y2xpZW50LWtleTpjbGllbnQtc2VjcmV0");
        assertThat(guestAuthorization.get()).isEqualTo("Bearer app-token");
        assertThat(guestClientVersion.get()).isEqualTo("12.19.1-release.0");
    }

    @Test
    void reportsHttpFailureWithoutIncludingResponseSecrets() {
        server.createContext("/oauth2/token", safeHandler(exchange ->
                respond(exchange, 401, "{\"secret\":\"must-not-leak\"}")));
        var client = createClient();

        assertThatThrownBy(() -> client.activate(deviceIdentity()))
                .isInstanceOf(XApiHttpException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("must-not-leak");
    }

    private GuestAuthenticationClient createClient() {
        var profile = new AndroidApiProfile(
                "com.twitter.android",
                "12.19.1-release.0",
                312191000,
                baseUri,
                baseUri.resolve("/graphql"),
                Map.of(
                        "X-Twitter-Client", "TwitterAndroid",
                        "X-Twitter-Client-Version", "12.19.1-release.0",
                        "Accept", "application/json"),
                Map.of(
                        "oauth2Token", "/oauth2/token",
                        "guestActivate", "/1.1/guest/activate.json"),
                Map.of());
        return new GuestAuthenticationClient(
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                profile,
                () -> new AndroidClientCredentials("client-key", "client-secret"),
                new AndroidRequestHeaders());
    }

    private static AndroidDeviceIdentity deviceIdentity() {
        return new AndroidDeviceIdentity(
                "TwitterAndroid/test", "client-uuid", "device-id", "ja", "2026-08-05");
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

    private static com.sun.net.httpserver.HttpHandler safeHandler(ThrowingExchangeHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable throwable) {
                respond(exchange, 500, "{\"handler_error\":\"" + throwable.getClass().getSimpleName() + "\"}");
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
