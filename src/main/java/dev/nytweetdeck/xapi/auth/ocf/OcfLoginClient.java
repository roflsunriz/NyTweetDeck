package dev.nytweetdeck.xapi.auth.ocf;

import dev.nytweetdeck.xapi.auth.GuestAuthenticationClient.GuestSession;
import dev.nytweetdeck.xapi.http.AndroidDeviceIdentity;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OcfLoginClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AndroidApiProfile profile;
    private final AndroidRequestHeaders androidRequestHeaders;
    private final OcfFlowParser flowParser;
    private final OcfSubtaskInputFactory inputFactory;

    @Autowired
    public OcfLoginClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            AndroidApiProfileService profileService,
            AndroidRequestHeaders androidRequestHeaders,
            OcfFlowParser flowParser,
            OcfSubtaskInputFactory inputFactory) {
        this(
                httpClient,
                objectMapper,
                profileService.profile(),
                androidRequestHeaders,
                flowParser,
                inputFactory);
    }

    OcfLoginClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            AndroidApiProfile profile,
            AndroidRequestHeaders androidRequestHeaders,
            OcfFlowParser flowParser,
            OcfSubtaskInputFactory inputFactory) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.profile = profile;
        this.androidRequestHeaders = androidRequestHeaders;
        this.flowParser = flowParser;
        this.inputFactory = inputFactory;
    }

    public LoginSession start(GuestSession guestSession, AndroidDeviceIdentity identity) {
        var body = createStartBody();
        var uri = onboardingUri(Map.of("flow_name", "login", "api_version", "1"));
        var flow = send(uri, body, guestSession, identity, "ログインフローの開始");
        return new LoginSession(guestSession, flow);
    }

    public LoginSession submit(
            LoginSession session,
            OcfSubtask subtask,
            OcfSubtaskInputFactory.Submission submission,
            AndroidDeviceIdentity identity) {
        var body = inputFactory.create(session.flow(), subtask, submission);
        var uri = onboardingUri(Map.of("api_version", "1"));
        var flow = send(uri, body, session.guestSession(), identity, "ログイン入力の送信");
        return new LoginSession(session.guestSession(), flow);
    }

    String createStartBody() {
        var versions = new LinkedHashMap<String, Integer>();
        versions.put("alert_dialog", 1);
        versions.put("open_account", 2);
        versions.put("enter_password", 5);
        versions.put("phone_verification", 5);
        versions.put("email_verification", 3);
        versions.put("enter_username", 3);
        versions.put("choice_selection", 5);
        versions.put("enter_text", 6);
        versions.put("enter_phone", 2);
        versions.put("enter_email", 2);
        versions.put("security_key", 3);
        versions.put("js_instrumentation", 1);
        versions.put("passkey", 1);
        versions.put("app_attestation", 1);
        try {
            return objectMapper.writeValueAsString(Map.of("subtask_versions", versions));
        } catch (JacksonException exception) {
            throw new IllegalStateException("OCF開始要求をJSONへ変換できません。", exception);
        }
    }

    private OcfFlow send(
            URI uri,
            String body,
            GuestSession guestSession,
            AndroidDeviceIdentity identity,
            String action) {
        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer " + guestSession.bearerToken())
                .header("X-Guest-Token", guestSession.guestToken())
                .header("Content-Type", "application/json");
        androidRequestHeaders.apply(requestBuilder, profile, identity);
        try {
            var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(
                        action + "に失敗しました。HTTP " + response.statusCode(), response.statusCode());
            }
            return flowParser.parse(response.body());
        } catch (IOException exception) {
            throw new XApiHttpException(action + "の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException(action + "が中断されました。", exception);
        }
    }

    private URI onboardingUri(Map<String, String> query) {
        var endpoint = profile.restEndpoints().get("onboardingTask");
        if (endpoint == null) {
            throw new IllegalStateException("onboardingTaskエンドポイントが未定義です。");
        }
        var queryString = String.join("&", query.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .toList());
        return URI.create(profile.restBaseUri().resolve(endpoint) + "?" + queryString);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record LoginSession(GuestSession guestSession, OcfFlow flow) {

        @Override
        public String toString() {
            return "LoginSession[guestSession=<redacted>, flow=" + flow + "]";
        }
    }
}
