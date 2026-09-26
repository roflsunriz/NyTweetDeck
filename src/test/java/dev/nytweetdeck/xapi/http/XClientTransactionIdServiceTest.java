package dev.nytweetdeck.xapi.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.nytweetdeck.account.AccountSecrets;
import dev.nytweetdeck.account.AccountStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class XClientTransactionIdServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void attachesSavedWebSessionHeadersForHome() {
        var account = AccountSecrets.webSession(
                "1", "1", "alice", "alice", "bearer-tok", "auth-tok", "csrf-tok");
        var builder = HttpRequest.newBuilder(URI.create("https://x.com/home"));

        XClientTransactionIdService.addSessionHeaders(builder, account);

        var request = builder.build();
        assertThat(request.headers().firstValue("Cookie"))
                .hasValue("auth_token=auth-tok; ct0=csrf-tok");
        assertThat(request.headers().firstValue("X-CSRF-Token")).hasValue("csrf-tok");
        assertThat(request.headers().firstValue("X-Twitter-Auth-Type")).hasValue("OAuth2Session");
        assertThat(request.headers().firstValue("X-Twitter-Active-User")).hasValue("yes");
    }

    @Test
    void resolvesCurrentOnDemandAssetFromTheWebRuntimeMap() {
        var html = "59924:\"ondemand.s\",other})[e]||e)+\".\"+({59924:\"e89b799f9742fd4e\"";

        assertThat(XClientTransactionIdService.resolveOnDemandUri(html))
                .isEqualTo(URI.create(
                        "https://abs.twimg.com/responsive-web/client-web/ondemand.s.e89b799f9742fd4ea.js"));
    }

    @Test
    void parsesTheVerificationKeyIndicesAndLoadingAnimation() {
        var keyBytes = new byte[24];
        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) (index + 1);
        }
        keyBytes[5] = 4;
        var key = Base64.getEncoder().encodeToString(keyBytes);
        var rows = new StringBuilder();
        for (int index = 0; index < 16; index++) {
            rows.append("C 10,20 30,40 50,60 70,80 90,100 110 ");
        }
        var frames = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            frames.append("<svg id=\"loading-x-anim-")
                    .append(index)
                    .append("\"><g><path d=\"M0\"></path><path d=\"M 10,30 ")
                    .append(rows)
                    .append("\"></path></g></svg>");
        }
        var html = "<meta name=\"twitter-site-verification\" content=\""
                + key
                + "\">"
                + frames;
        var source = "(a[2], 16)(b[17], 16)(c[3], 16)(d[9], 16)";

        var material = XClientTransactionIdService.parseSigningMaterial(html, source);

        assertThat(material.keyBytes()).containsExactly(keyBytes);
        assertThat(material.animationKey()).isNotBlank().doesNotContain(".", "-");
    }

    @Test
    void encodesTheMethodPathTimestampHashAndRandomMaskDeterministically() {
        var encoded = XClientTransactionIdService.encode(
                "POST",
                "/i/api/graphql/id/CreateRetweet",
                Base64.getDecoder().decode("AQIDBAUGBwg="),
                "abcdef",
                123_456_789L,
                42);

        assertThat(encoded).isEqualTo("KisoKS4vLC0iP+dxLVBSfQNSOMLgRgeCHetFQtUp");
    }

    @Test
    void fetchesHomeWithSavedWebSessionInsteadOfRedirectingToLogin() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var cookies = new CopyOnWriteArrayList<String>();
        var homeHtml = signingHomeHtml();
        server.createContext("/home", exchange -> {
            var cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                cookies.add(cookie);
            }
            byte[] body;
            int status;
            if (cookie != null && cookie.contains("auth_token=auth-tok")) {
                status = 200;
                body = homeHtml.getBytes(StandardCharsets.UTF_8);
            } else {
                status = 307;
                exchange.getResponseHeaders().add("Location", "/login");
                body = new byte[0];
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/ondemand.s.hasha.js", exchange -> {
            var body = "(a[2], 16)(b[17], 16)".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var store = new AccountStore(
                    JsonMapper.builder().build(), temporaryDirectory.resolve("accounts.json"));
            store.addOrReplace(AccountSecrets.webSession(
                    "1", "1", "alice", "alice", "bearer-tok", "auth-tok", "csrf-tok"));
            var service = new XClientTransactionIdService(
                    HttpClient.newHttpClient(),
                    store,
                    URI.create(base + "/home"),
                    URI.create(base + "/"),
                    Clock.fixed(Instant.ofEpochSecond(1_682_924_400L + 123_456_789L), ZoneOffset.UTC),
                    () -> 42,
                    Duration.ofMinutes(30));

            var transactionId = service.generate("POST", URI.create("https://x.com/i/api/graphql/id/Op"));

            assertThat(transactionId).isNotBlank();
            assertThat(cookies).anyMatch(cookie -> cookie.contains("auth_token=auth-tok"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectToLoginWithoutSavedSession() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/home", exchange -> {
            exchange.getResponseHeaders().add("Location", "/login");
            exchange.sendResponseHeaders(307, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var store = new AccountStore(
                    JsonMapper.builder().build(), temporaryDirectory.resolve("accounts.json"));
            var service = new XClientTransactionIdService(
                    HttpClient.newHttpClient(),
                    store,
                    URI.create(base + "/home"),
                    URI.create(base + "/"),
                    Clock.systemUTC(),
                    () -> 42,
                    Duration.ofMinutes(30));

            assertThatThrownBy(() ->
                            service.generate("POST", URI.create("https://x.com/i/api/graphql/id/Op")))
                    .isInstanceOf(XApiHttpException.class)
                    .hasMessageContaining("ログイン");
        } finally {
            server.stop(0);
        }
    }

    private static String signingHomeHtml() {
        var keyBytes = new byte[24];
        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) (index + 1);
        }
        var key = Base64.getEncoder().encodeToString(keyBytes);
        var rows = new StringBuilder();
        for (int index = 0; index < 16; index++) {
            rows.append("C 10,20 30,40 50,60 70,80 90,100 110 ");
        }
        var frames = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            frames.append("<svg id=\"loading-x-anim-")
                    .append(index)
                    .append("\"><g><path d=\"M0\"></path><path d=\"M 10,30 ")
                    .append(rows)
                    .append("\"></path></g></svg>");
        }
        return "59924:\"ondemand.s\",59924:\"hash\","
                + "<meta name=\"twitter-site-verification\" content=\""
                + key
                + "\">"
                + frames;
    }
}
