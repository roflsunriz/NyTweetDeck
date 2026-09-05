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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import java.util.Properties;
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
        assertThat(release.currentVersion()).isEqualTo("1.4.0");
        assertThat(release.updateAvailable()).isTrue();
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

    @ParameterizedTest
    @CsvSource({"1.4.1,false", "1.4.2,false", "2.0.0,false", "1.4.0,true", "1.3.99,true",
            "1.4.1-SNAPSHOT,false", "1.4.0-SNAPSHOT,true"})
    void onlyOffersVersionsNewerThanTheRunningBuild(String current, boolean available) {
        responseBody = "[" + releaseJson("1.4.1", "2026-09-04T00:00:00Z") + "]";
        var release = service(current).latestStableRelease();
        assertThat(release.updateAvailable()).isEqualTo(available);
        assertThat(release.currentVersion()).isEqualTo(current);
    }

    @Test
    void selectsNumericVersionInsteadOfPublicationDateOrLexicalOrder() {
        responseBody = "[" + releaseJson("1.9.99", "2026-09-05T00:00:00Z") + ","
                + releaseJson("1.10.0", "2026-09-01T00:00:00Z") + ","
                + releaseJson("1.11.0-beta.1", "2026-09-06T00:00:00Z") + "]";
        assertThat(service("1.10.0").latestStableRelease().tagName()).isEqualTo("v1.10.0");
        assertThat(service("1.10.0").latestStableRelease().updateAvailable()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "${revision}", "1.4", "1.4.1-custom", "01.4.1"})
    void failsBeforeNetworkRequestIfRunningVersionCannotBeCompared(String version) {
        assertThatThrownBy(() -> service(version).latestStableRelease())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("build version");
        assertThat(accept).isNull();
    }

    @Test
    void buildInfoContainsTheActualMavenRevision() throws IOException {
        var properties = new Properties();
        try (var stream = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
            assertThat(stream).isNotNull();
            properties.load(stream);
        }
        assertThat(properties.getProperty("build.version"))
                .isEqualTo(System.getProperty("revision", "1.0.0-SNAPSHOT"));
    }

    @Test
    void missingBuildMetadataFailsSafely() {
        var service = new DesktopReleaseService(HttpClient.newHttpClient(), JsonMapper.builder().build(),
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class));
        assertThatThrownBy(service::latestStableRelease).hasMessageContaining("build version");
    }

    private String releaseJson(String version, String publishedAt) {
        return """
                {"tag_name":"v%s","published_at":"%s","draft":false,"prerelease":false,"assets":[
                {"name":"NyTweetDeck-v%s.zip","state":"uploaded","browser_download_url":"https://github.com/roflsunriz/NyTweetDeck/releases/download/v%s/NyTweetDeck-v%s.zip","size":123}]}
                """.formatted(version, publishedAt, version, version, version);
    }

    private DesktopReleaseService service() {
        return service("1.4.0");
    }

    private DesktopReleaseService service(String version) {
        return new DesktopReleaseService(
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases"), version);
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
