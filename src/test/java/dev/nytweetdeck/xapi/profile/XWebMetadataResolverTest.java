package dev.nytweetdeck.xapi.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.nytweetdeck.account.AccountSecrets;
import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class XWebMetadataResolverTest {

    @TempDir
    Path temporaryDirectory;

    private final XWebMetadataResolver resolver = new XWebMetadataResolver(null);

    @Test
    void extractsOperationMetadataWithoutExecutingOfficialJavascript() {
        var operations = resolver.parseOperations("""
                436870(e){e.exports={queryId:"wp06oo3fRGU4P1sK8rECqQ",operationName:"HomeTimeline",
                operationType:"query",metadata:{featureSwitches:["feature_a","feature_b"],
                fieldToggles:["withArticlePlainText"]}}}
                """);

        assertThat(operations).containsKey("HomeTimeline");
        var operation = operations.get("HomeTimeline");
        assertThat(operation.operationId()).isEqualTo("wp06oo3fRGU4P1sK8rECqQ");
        assertThat(operation.featureKeys()).containsExactly("feature_a", "feature_b");
        assertThat(operation.fieldToggles()).containsExactly("withArticlePlainText");
    }

    @Test
    void extractsBooleanFeatureDefaultsAndSafeChunkManifestEntries() {
        var html = """
                {1:"bundle.HomeTimeline",2:"icons.1",1:"0123456789abcdef",2:"fedcba9876543210"}
                "feature_a":{"value":true},"feature_b":{"value":false}
                """;

        assertThat(resolver.parseBooleanFeatures(html))
                .containsEntry("feature_a", true)
                .containsEntry("feature_b", false);
        assertThat(resolver.parseChunkCandidates(html))
                .extracting(XWebMetadataResolver.ChunkCandidate::name)
                .contains("bundle.HomeTimeline", "icons.1");
    }

    @Test
    void resolvesOperationsWithSavedWebSessionInsteadOfRedirectingToLogin() throws Exception {
        var observedCookies = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var port = server.getAddress().getPort();
        server.createContext("/home", exchange -> {
            var cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                observedCookies.add(cookie);
            }
            byte[] body;
            int status;
            if (cookie != null && cookie.contains("auth_token=auth-tok")) {
                status = 200;
                body = ("<html><script src=\"http://127.0.0.1:"
                                + port
                                + "/client-web/main.test.js\"></script>"
                                + "\"feature_a\":{\"value\":true}</html>")
                        .getBytes(StandardCharsets.UTF_8);
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
        server.createContext("/client-web/main.test.js", exchange -> {
            var body = ("436870(e){e.exports={queryId:\"testQueryId01\","
                            + "operationName:\"NotificationsTimeline\",operationType:\"query\","
                            + "metadata:{featureSwitches:[\"feature_a\"],fieldToggles:[]}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var store = new AccountStore(
                    JsonMapper.builder().build(), temporaryDirectory.resolve("accounts.json"));
            store.addOrReplace(AccountSecrets.webSession(
                    "a", "u", "name", "display", "bearer-tok", "auth-tok", "csrf-tok"));
            var local = new XWebMetadataResolver(
                    HttpClient.newHttpClient(),
                    store,
                    URI.create("http://127.0.0.1:" + port + "/home"),
                    URI.create("http://127.0.0.1:" + port + "/client-web/"),
                    true);

            var metadata = local.resolve(Set.of("NotificationsTimeline"));

            assertThat(metadata.operationsByName())
                    .containsKey("NotificationsTimeline");
            assertThat(metadata.operationsByName().get("NotificationsTimeline").operationId())
                    .isEqualTo("testQueryId01");
            assertThat(metadata.missingOperations()).isEmpty();
            assertThat(metadata.sourceVersion()).isEqualTo("main.test.js");
            assertThat(observedCookies).anyMatch(cookie -> cookie.contains("auth_token=auth-tok"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsMissingOperationsInsteadOfFailingTheWholeRefresh() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var port = server.getAddress().getPort();
        server.createContext("/home", exchange -> {
            var body = ("<html><script src=\"http://127.0.0.1:"
                            + port
                            + "/client-web/main.test.js\"></script></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/client-web/main.test.js", exchange -> {
            var body = ("436870(e){e.exports={queryId:\"testQueryId01\","
                            + "operationName:\"NotificationsTimeline\",operationType:\"query\","
                            + "metadata:{featureSwitches:[],fieldToggles:[]}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var store = new AccountStore(
                    JsonMapper.builder().build(), temporaryDirectory.resolve("accounts.json"));
            var local = new XWebMetadataResolver(
                    HttpClient.newHttpClient(),
                    store,
                    URI.create("http://127.0.0.1:" + port + "/home"),
                    URI.create("http://127.0.0.1:" + port + "/client-web/"),
                    true);

            var metadata = local.resolve(Set.of("NotificationsTimeline", "ListLatestTweetsTimeline"));

            assertThat(metadata.operationsByName()).containsOnlyKeys("NotificationsTimeline");
            assertThat(metadata.missingOperations()).containsExactly("ListLatestTweetsTimeline");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectToLoginWithoutSavedSession() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var port = server.getAddress().getPort();
        server.createContext("/home", exchange -> {
            exchange.getResponseHeaders().add("Location", "/login");
            exchange.sendResponseHeaders(307, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            var store = new AccountStore(
                    JsonMapper.builder().build(), temporaryDirectory.resolve("accounts.json"));
            var local = new XWebMetadataResolver(
                    HttpClient.newHttpClient(),
                    store,
                    URI.create("http://127.0.0.1:" + port + "/home"),
                    URI.create("http://127.0.0.1:" + port + "/client-web/"),
                    true);

            assertThatThrownBy(() -> local.resolve(Set.of("NotificationsTimeline")))
                    .isInstanceOf(XApiHttpException.class)
                    .hasMessageContaining("ログイン");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoversListOperationsFromListChunks() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var port = server.getAddress().getPort();
        server.createContext("/home", exchange -> {
            var body = ("<html><script src=\"http://127.0.0.1:"
                            + port
                            + "/client-web/main.test.js\"></script>"
                            + "{1:\"bundle.UserLists\",1:\"0123456789abcdef\"}</html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/client-web/main.test.js", exchange -> {
            var body = "no operations here".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/client-web/bundle.UserLists.0123456789abcdefa.js", exchange -> {
            var body = ("436870(e){e.exports={queryId:\"listQueryId01\","
                            + "operationName:\"ListLatestTweetsTimeline\",operationType:\"query\","
                            + "metadata:{featureSwitches:[],fieldToggles:[]}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var store = new AccountStore(
                    JsonMapper.builder().build(), temporaryDirectory.resolve("accounts.json"));
            var local = new XWebMetadataResolver(
                    HttpClient.newHttpClient(),
                    store,
                    URI.create("http://127.0.0.1:" + port + "/home"),
                    URI.create("http://127.0.0.1:" + port + "/client-web/"),
                    true);

            var metadata = local.resolve(Set.of("ListLatestTweetsTimeline"));

            assertThat(metadata.operationsByName().get("ListLatestTweetsTimeline").operationId())
                    .isEqualTo("listQueryId01");
            assertThat(metadata.missingOperations()).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
