package dev.nytweetdeck.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DesktopReleaseServiceTest {

    private HttpServer server;
    private volatile String accept;
    private volatile String apiVersion;
    private volatile String userAgent;
    private volatile int statusCode = 200;
    private volatile String responseBody = "[]";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void selectsTheNewestPublishedStableDesktopZipAndSendsVersionedHeaders() {
        responseBody = """
                [
                  {"tag_name":"android-v0.2.2","published_at":"2026-09-05T00:00:00Z","draft":false,"prerelease":false,"assets":[]},
                  {"tag_name":"v1.4.1","published_at":"2026-09-04T00:00:00Z","draft":false,"prerelease":false,"assets":[
                    {"name":"NyTweetDeck-v1.4.1.zip","state":"uploaded","browser_download_url":"https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip","size":123456}
                  ]},
                  {"tag_name":"v1.5.0-beta.1","published_at":"2026-09-03T00:00:00Z","draft":false,"prerelease":true,"assets":[]}
                ]
                """;

        var release = service().latestStableRelease();

        assertThat(release.tagName()).isEqualTo("v1.4.1");
        assertThat(release.assetName()).isEqualTo("NyTweetDeck-v1.4.1.zip");
        assertThat(release.sizeBytes()).isEqualTo(123456L);
        assertThat(accept).isEqualTo("application/vnd.github+json");
        assertThat(apiVersion).isEqualTo("2022-11-28");
        assertThat(userAgent).isEqualTo("NyTweetDeck-Desktop");
    }

    @Test
    void rejectsCrossRepositoryDownloadUrlsAndApiFailures() {
        responseBody = """
                [{"tag_name":"v1.4.1","published_at":"2026-09-04T00:00:00Z","draft":false,"prerelease":false,"assets":[
                  {"name":"NyTweetDeck-v1.4.1.zip","state":"uploaded","browser_download_url":"https://github.com/other/repository/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip","size":123}
                ]}]
                """;
        assertThatThrownBy(() -> service().latestStableRelease())
                .isInstanceOf(IllegalStateException.class);

        statusCode = 403;
        assertThatThrownBy(() -> service().latestStableRelease())
                .isInstanceOf(IllegalStateException.class);
    }

    private DesktopReleaseService service() {
        return new DesktopReleaseService(
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases"));
    }

    private void respond(HttpExchange exchange) throws IOException {
        accept = exchange.getRequestHeaders().getFirst("Accept");
        apiVersion = exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version");
        userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }
}
