package dev.nytweetdeck;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NyTweetDeckApplicationTest {

    private static final Path ACCOUNT_STORE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "nytweetdeck-test-" + UUID.randomUUID() + ".json");
    private static final Path SETTINGS_STORE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "nytweetdeck-settings-test-" + UUID.randomUUID() + ".json");

    @DynamicPropertySource
    static void accountStoreProperties(DynamicPropertyRegistry registry) {
        registry.add("nytweetdeck.account.store-path", ACCOUNT_STORE_PATH::toString);
        registry.add("nytweetdeck.settings.store-path", SETTINGS_STORE_PATH::toString);
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Test
    void servesStatusApiWithSecurityHeaders() throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/system/status"))
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ready\"").contains("\"apiVersion\":1");
        assertThat(response.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void servesBundledFrontend() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var bundledFrontend = new ClassPathResource("static/index.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("text/html"));
        assertThat(response.body()).startsWith("<!doctype html>");
        assertThat(bundledFrontend).contains("NyTweetDeck").contains("id=\"root\"");
    }

    @Test
    void servesPublicWebApiProfileWithoutCredentials() throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/x-api/profile"))
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"packageName\":\"x-web\"")
                .contains("\"homeForYou\"")
                .doesNotContainIgnoringCase("consumerSecret")
                .doesNotContainIgnoringCase("Authorization");
    }

    @Test
    void servesTheAutomaticallyLoadedAccountListWithoutSetup() throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/accounts"))
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }

    @Test
    void servesCredentialFreeTranslationReliabilityMetrics() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/system/translation-health"))
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"upstreamRequests\":0")
                .contains("\"rateLimitedResponses\":0")
                .doesNotContainIgnoringCase("accountId")
                .doesNotContainIgnoringCase("postId")
                .doesNotContainIgnoringCase("token");
    }

    @Test
    void sharesOneLayoutBetweenIpAndHostnameOrigins() throws Exception {
        var layout = """
                {
                  "expectedRevision": 0,
                  "layout": {
                    "version": 7,
                    "columns": [{"id":"shared-home","kind":"home","target":null,"label":null}],
                    "navItems": ["home","trends"],
                    "locale": "ja",
                    "theme": "dark",
                    "activeAccountId": null,
                    "display": {
                      "fontSize":"default","accentColor":"blue","density":"comfortable",
                      "reduceMotion":false,"mediaPreview":true,"videoAutoplay":false,
                      "videoLoop":true,"videoVolume":100,"autoTranslatePosts":true
                    },
                    "trendSearchHistory": ["shared-origin"]
                  }
                }
                """;
        var saveRequest = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/settings/layout"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://127.0.0.1:" + port)
                .header("Sec-Fetch-Site", "same-origin")
                .PUT(HttpRequest.BodyPublishers.ofString(layout))
                .build();

        var saved = client.send(saveRequest, HttpResponse.BodyHandlers.ofString());
        var loadRequest = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/settings/layout"))
                .header("Origin", "http://localhost:" + port)
                .header("Sec-Fetch-Site", "same-origin")
                .GET()
                .build();
        var loaded = client.send(loadRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(loaded.statusCode()).isEqualTo(200);
        assertThat(loaded.body())
                .contains("\"revision\":1")
                .contains("\"id\":\"shared-home\"")
                .contains("\"trendSearchHistory\":[\"shared-origin\"]");
        assertThat(loaded.headers().firstValue("Cache-Control")).contains("no-store");
    }
}
