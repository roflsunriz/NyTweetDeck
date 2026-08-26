package dev.nytweetdeck.xapi.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nytweetdeck.xapi.credentials.AndroidClientCredentials;
import dev.nytweetdeck.xapi.credentials.AndroidClientCredentialsProvider;
import dev.nytweetdeck.xapi.http.AndroidDeviceIdentity;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class GuestAuthenticationClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AndroidApiProfile profile;
    private final Supplier<AndroidClientCredentials> credentialsSupplier;
    private final AndroidRequestHeaders androidRequestHeaders;

    @Autowired
    public GuestAuthenticationClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            AndroidApiProfileService profileService,
            AndroidClientCredentialsProvider credentialsProvider,
            AndroidRequestHeaders androidRequestHeaders) {
        this(
                httpClient,
                objectMapper,
                profileService.profile(),
                credentialsProvider::require,
                androidRequestHeaders);
    }

    GuestAuthenticationClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            AndroidApiProfile profile,
            Supplier<AndroidClientCredentials> credentialsSupplier,
            AndroidRequestHeaders androidRequestHeaders) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.profile = profile;
        this.credentialsSupplier = credentialsSupplier;
        this.androidRequestHeaders = androidRequestHeaders;
    }

    public GuestSession activate(AndroidDeviceIdentity identity) {
        var bearerToken = requestBearerToken(identity);
        var endpoint = resolveRestEndpoint("guestActivate");
        var requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Bearer " + bearerToken);
        androidRequestHeaders.apply(requestBuilder, profile, identity);
        var response = send(requestBuilder.build(), "Guest Tokenの取得");
        var guestResponse = read(response.body(), GuestTokenResponse.class, "Guest Token応答");
        if (guestResponse.guestToken() == null || guestResponse.guestToken().isBlank()) {
            throw new XApiHttpException("Guest Token応答にguest_tokenがありません。", response.statusCode());
        }
        return new GuestSession(bearerToken, guestResponse.guestToken());
    }

    private String requestBearerToken(AndroidDeviceIdentity identity) {
        var credentials = credentialsSupplier.get();
        var rawCredentials = credentials.consumerKey() + ":" + credentials.consumerSecret();
        var basicCredentials = Base64.getEncoder()
                .encodeToString(rawCredentials.getBytes(StandardCharsets.UTF_8));
        var endpoint = resolveRestEndpoint("oauth2Token");
        var requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .header("Authorization", "Basic " + basicCredentials)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        androidRequestHeaders.apply(requestBuilder, profile, identity);
        var response = send(requestBuilder.build(), "Bearer Tokenの取得");
        var tokenResponse = read(response.body(), OAuth2TokenResponse.class, "Bearer Token応答");
        if (!"bearer".equalsIgnoreCase(tokenResponse.tokenType())
                || tokenResponse.accessToken() == null
                || tokenResponse.accessToken().isBlank()) {
            throw new XApiHttpException("Bearer Token応答の形式が不正です。", response.statusCode());
        }
        return tokenResponse.accessToken();
    }

    private URI resolveRestEndpoint(String name) {
        var path = profile.restEndpoints().get(name);
        if (path == null) {
            throw new IllegalStateException("RESTエンドポイントが未定義です: " + name);
        }
        return profile.restBaseUri().resolve(path);
    }

    private HttpResponse<String> send(HttpRequest request, String action) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(
                        action + "に失敗しました。HTTP " + response.statusCode(), response.statusCode());
            }
            return response;
        } catch (IOException exception) {
            throw new XApiHttpException(action + "の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException(action + "が中断されました。", exception);
        }
    }

    private <T> T read(String body, Class<T> type, String source) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JacksonException exception) {
            throw new XApiHttpException(source + "を解析できません。", exception);
        }
    }

    private record OAuth2TokenResponse(
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("access_token") String accessToken) {}

    private record GuestTokenResponse(@JsonProperty("guest_token") String guestToken) {}

    public record GuestSession(String bearerToken, String guestToken) {

        @Override
        public String toString() {
            return "GuestSession[bearerToken=<redacted>, guestToken=<redacted>]";
        }
    }
}
