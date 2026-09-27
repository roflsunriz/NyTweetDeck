package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nytweetdeck.account.AccountSecrets;
import dev.nytweetdeck.account.AccountStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

class ComposerWebClientTest {
    @TempDir Path temporary;
    private HttpServer server;
    private ComposerWebClient composer;
    private final List<String> uploadCommands = new ArrayList<>();
    private String uploadContentType;
    private String pollCardData;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            try {
            var query = exchange.getRequestURI().getRawQuery();
            var command = query.split("command=")[1].split("&")[0];
            uploadCommands.add(command);
            if ("APPEND".equals(command)) {
                uploadContentType = exchange.getRequestHeaders().getFirst("Content-Type");
                assertThat(exchange.getRequestBody().readAllBytes()).containsSequence(new byte[] {
                        (byte) 137, 80, 78, 71, 13, 10, 26, 10});
            }
            var body = switch (command) {
                case "INIT" -> "{\"media_id_string\":\"42\"}";
                case "APPEND", "FINALIZE" -> "";
                default -> "{}";
            };
            respond(exchange, body);
            } catch (Exception failure) {
                System.err.println("upload handler failed: " + failure.getClass().getName() + " " + failure.getMessage());
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.createContext("/poll", exchange -> {
            try {
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            pollCardData = URLDecoder.decode(form.substring("card_data=".length()), StandardCharsets.UTF_8);
            respond(exchange, "{\"status\":\"OK\",\"card_uri\":\"card://42\"}");
            } catch (Exception failure) {
                System.err.println("poll handler failed: " + failure.getClass().getName() + " " + failure.getMessage());
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.createContext("/geo", exchange -> respond(exchange,
                "{\"places\":[{\"place\":{\"id\":\"abc123\",\"full_name\":\"Tokyo, Japan\","
                        + "\"country\":\"Japan\"},\"geo_search_request_id\":\"request1\"}]}"));
        server.start();
        var mapper = JsonMapper.builder().build();
        var store = new AccountStore(mapper, temporary.resolve("accounts.json"));
        store.addOrReplace(new AccountSecrets("7", "7", "user", "User", "bearer", "auth", "csrf"));
        var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        composer = new ComposerWebClient(HttpClient.newHttpClient(), store, mapper,
                base.resolve("/upload"), base.resolve("/poll"), base.resolve("/geo"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void uploadsAnImageBeforeTheMediaIdCanBeAttachedToAPost() {
        var png = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        var result = composer.upload("7", new MockMultipartFile("file", "photo.png", "image/png", png));

        assertThat(result.mediaId()).isEqualTo("42");
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(uploadCommands).containsExactly("INIT", "APPEND", "FINALIZE");
        assertThat(uploadContentType).startsWith("multipart/form-data; boundary=");
    }

    @Test
    void createsTheNativePollCardAndReadsPlaceCandidates() throws Exception {
        assertThat(composer.createPoll("7", new PostComposition.Poll(List.of("yes", "no"), 60)))
                .isEqualTo("card://42");
        assertThat(JsonMapper.builder().build().readTree(pollCardData).path("twitter:card").asString())
                .isEqualTo("poll2choice_text_only");
        var card = ComposerWebClient.pollCardData(new PostComposition.Poll(List.of("yes", "no"), 60));
        assertThat(card).containsEntry("twitter:card", "poll2choice_text_only")
                .containsEntry("twitter:long:duration_minutes", 60)
                .containsEntry("twitter:string:choice2_label", "no");
        assertThat(composer.searchPlaces("7", "Tokyo"))
                .containsExactly(new ComposerWebClient.Place("abc123", "Tokyo, Japan", "Japan", "request1"));
    }

    @Test
    void rejectsUnsupportedMediaAndIncompletePollBeforeCallingX() {
        assertThatThrownBy(() -> composer.upload("7",
                new MockMultipartFile("file", "bad.txt", "text/plain", "no image".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> composer.createPoll("7", new PostComposition.Poll(List.of("only one"), 60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(uploadCommands).isEmpty();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
