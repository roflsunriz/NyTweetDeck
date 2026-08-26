package dev.nytweetdeck.xapi.auth.ocf;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nytweetdeck.xapi.auth.GuestAuthenticationClient.GuestSession;
import dev.nytweetdeck.xapi.auth.ocf.OcfSubtaskInputFactory.Submission;
import dev.nytweetdeck.xapi.http.AndroidDeviceIdentity;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OcfLoginClientTest {

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
    void startsAndContinuesDynamicLoginFlowWithGuestHeaders() {
        var requestCount = new AtomicInteger();
        var firstQuery = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var guestToken = new AtomicReference<String>();
        server.createContext("/1.1/onboarding/task.json", exchange -> {
            var current = requestCount.incrementAndGet();
            if (current == 1) {
                firstQuery.set(exchange.getRequestURI().getRawQuery());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                guestToken.set(exchange.getRequestHeaders().getFirst("X-Guest-Token"));
                respond(exchange, """
                        {"flow_token":"flow-1","subtasks":[{
                          "subtask_id":"LoginEnterUserIdentifierSSO",
                          "enter_text":{"next_link":{"link_id":"next_link"}}
                        }]}
                        """);
            } else {
                respond(exchange, """
                        {"flow_token":"flow-2","subtasks":[{
                          "subtask_id":"LoginEnterPassword","enter_password":{}
                        }]}
                        """);
            }
        });
        var client = createClient();
        var guest = new GuestSession("bearer-token", "guest-token");

        var first = client.start(guest, deviceIdentity());
        var next = client.submit(
                first,
                first.flow().subtasks().get(0),
                new Submission("user@example.com", List.of(), null),
                deviceIdentity());

        assertThat(firstQuery.get()).isEqualTo("api_version=1&flow_name=login");
        assertThat(authorization.get()).isEqualTo("Bearer bearer-token");
        assertThat(guestToken.get()).isEqualTo("guest-token");
        assertThat(first.flow().subtasks().get(0).type()).isEqualTo(OcfSubtaskType.TEXT);
        assertThat(next.flow().flowToken()).isEqualTo("flow-2");
        assertThat(next.flow().subtasks().get(0).type()).isEqualTo(OcfSubtaskType.PASSWORD);
        assertThat(next.toString()).doesNotContain("bearer-token", "guest-token", "flow-2");
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void startBodyUsesVersionsConfirmedFromAndroidApk() throws Exception {
        var client = createClient();
        var tree = JsonMapper.builder().build().readTree(client.createStartBody());
        var versions = tree.get("subtask_versions");

        assertThat(versions.get("enter_password").asInt()).isEqualTo(5);
        assertThat(versions.get("enter_text").asInt()).isEqualTo(6);
        assertThat(versions.get("app_attestation").asInt()).isEqualTo(1);
        assertThat(versions.get("passkey").asInt()).isEqualTo(1);
    }

    private OcfLoginClient createClient() {
        var jsonMapper = JsonMapper.builder().build();
        var profile = new AndroidApiProfile(
                "com.twitter.android",
                "12.19.1-release.0",
                312191000,
                baseUri,
                baseUri.resolve("/graphql"),
                Map.of("X-Twitter-Client", "TwitterAndroid"),
                Map.of("onboardingTask", "/1.1/onboarding/task.json"),
                Map.of());
        return new OcfLoginClient(
                HttpClient.newHttpClient(),
                jsonMapper,
                profile,
                new AndroidRequestHeaders(),
                new OcfFlowParser(jsonMapper),
                new OcfSubtaskInputFactory(jsonMapper));
    }

    private static AndroidDeviceIdentity deviceIdentity() {
        return new AndroidDeviceIdentity(
                "TwitterAndroid/test", "client-uuid", "device-id", "ja", "2026-08-05");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
