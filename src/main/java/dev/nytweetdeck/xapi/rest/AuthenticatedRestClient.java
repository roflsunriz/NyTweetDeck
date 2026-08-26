package dev.nytweetdeck.xapi.rest;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.xapi.credentials.AndroidClientCredentialsProvider;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfileStore;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer.Credentials;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer.Parameter;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedRestClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final AndroidApiProfileService profileService;
    private final AndroidClientCredentialsProvider clientCredentialsProvider;
    private final AndroidDeviceProfileStore deviceProfileStore;
    private final AccountVaultSessionManager vaultSessionManager;
    private final AndroidRequestHeaders androidRequestHeaders;
    private final OAuth1Signer oauth1Signer;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthenticatedRestClient(
            HttpClient httpClient,
            AndroidApiProfileService profileService,
            AndroidClientCredentialsProvider clientCredentialsProvider,
            AndroidDeviceProfileStore deviceProfileStore,
            AccountVaultSessionManager vaultSessionManager,
            AndroidRequestHeaders androidRequestHeaders,
            OAuth1Signer oauth1Signer) {
        this.httpClient = httpClient;
        this.profileService = profileService;
        this.clientCredentialsProvider = clientCredentialsProvider;
        this.deviceProfileStore = deviceProfileStore;
        this.vaultSessionManager = vaultSessionManager;
        this.androidRequestHeaders = androidRequestHeaders;
        this.oauth1Signer = oauth1Signer;
    }

    public RestResult get(String accountId, String endpointName, Map<String, String> parameters) {
        var profile = profileService.profile();
        var path = profile.restEndpoints().get(endpointName);
        if (path == null) {
            throw new IllegalArgumentException("未定義のRESTエンドポイントです: " + endpointName);
        }
        var requestUri = withQuery(profile.restBaseUri().resolve(path), parameters);
        var account = vaultSessionManager.requireAccount(accountId);
        var clientCredentials = clientCredentialsProvider.require();
        var oauthCredentials = new Credentials(
                clientCredentials.consumerKey(),
                clientCredentials.consumerSecret(),
                account.oauthToken(),
                account.oauthTokenSecret());
        var authorization = oauth1Signer.authorizationHeader(
                "GET",
                requestUri,
                java.util.List.of(),
                oauthCredentials,
                Long.toString(Instant.now().getEpochSecond()),
                newNonce());
        var requestBuilder = HttpRequest.newBuilder(requestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .GET();
        androidRequestHeaders.apply(
                requestBuilder, profile, deviceProfileStore.require().toIdentity(profile));
        return send(requestBuilder.build(), endpointName);
    }

    public RestResult postForm(
            String accountId, String endpointName, Map<String, String> parameters) {
        var profile = profileService.profile();
        var path = profile.restEndpoints().get(endpointName);
        if (path == null) {
            throw new IllegalArgumentException("未定義のRESTエンドポイントです: " + endpointName);
        }
        var requestUri = profile.restBaseUri().resolve(path);
        var bodyParameters = parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Parameter(entry.getKey(), entry.getValue()))
                .toList();
        var body = formBody(bodyParameters);
        var account = vaultSessionManager.requireAccount(accountId);
        var clientCredentials = clientCredentialsProvider.require();
        var oauthCredentials = new Credentials(
                clientCredentials.consumerKey(),
                clientCredentials.consumerSecret(),
                account.oauthToken(),
                account.oauthTokenSecret());
        var authorization = oauth1Signer.authorizationHeader(
                "POST",
                requestUri,
                bodyParameters,
                oauthCredentials,
                Long.toString(Instant.now().getEpochSecond()),
                newNonce());
        var requestBuilder = HttpRequest.newBuilder(requestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        androidRequestHeaders.apply(
                requestBuilder, profile, deviceProfileStore.require().toIdentity(profile));
        return send(requestBuilder.build(), endpointName);
    }

    static String formBody(List<Parameter> parameters) {
        return String.join("&", parameters.stream()
                .map(parameter -> encode(parameter.name()) + "=" + encode(parameter.value()))
                .toList());
    }

    private RestResult send(HttpRequest request, String endpointName) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(
                        "REST " + endpointName + "に失敗しました。HTTP " + response.statusCode(),
                        response.statusCode());
            }
            return new RestResult(endpointName, response.body());
        } catch (IOException exception) {
            throw new XApiHttpException("REST " + endpointName + "の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException("REST " + endpointName + "が中断されました。", exception);
        }
    }

    private String newNonce() {
        var bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static URI withQuery(URI uri, Map<String, String> parameters) {
        if (parameters.isEmpty()) {
            return uri;
        }
        var query = String.join("&", parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .toList());
        return URI.create(uri + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record RestResult(String endpointName, String rawJson) {

        @Override
        public String toString() {
            return "RestResult[endpointName=" + endpointName + ", rawJson=<redacted>]";
        }
    }
}
