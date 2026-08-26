package dev.nytweetdeck.xapi.graphql;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.xapi.credentials.AndroidClientCredentialsProvider;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfileStore;
import dev.nytweetdeck.xapi.http.AndroidRequestHeaders;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer.Credentials;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile.OperationType;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import dev.nytweetdeck.xapi.profile.AndroidFeatureDefaultsService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthenticatedGraphQlClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AndroidApiProfileService profileService;
    private final AndroidFeatureDefaultsService featureDefaultsService;
    private final AndroidClientCredentialsProvider clientCredentialsProvider;
    private final AndroidDeviceProfileStore deviceProfileStore;
    private final AccountVaultSessionManager vaultSessionManager;
    private final AndroidRequestHeaders androidRequestHeaders;
    private final OAuth1Signer oauth1Signer;
    private final SecureRandom secureRandom;

    @Autowired
    public AuthenticatedGraphQlClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            AndroidApiProfileService profileService,
            AndroidFeatureDefaultsService featureDefaultsService,
            AndroidClientCredentialsProvider clientCredentialsProvider,
            AndroidDeviceProfileStore deviceProfileStore,
            AccountVaultSessionManager vaultSessionManager,
            AndroidRequestHeaders androidRequestHeaders,
            OAuth1Signer oauth1Signer) {
        this(
                httpClient,
                objectMapper,
                profileService,
                featureDefaultsService,
                clientCredentialsProvider,
                deviceProfileStore,
                vaultSessionManager,
                androidRequestHeaders,
                oauth1Signer,
                new SecureRandom());
    }

    AuthenticatedGraphQlClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            AndroidApiProfileService profileService,
            AndroidFeatureDefaultsService featureDefaultsService,
            AndroidClientCredentialsProvider clientCredentialsProvider,
            AndroidDeviceProfileStore deviceProfileStore,
            AccountVaultSessionManager vaultSessionManager,
            AndroidRequestHeaders androidRequestHeaders,
            OAuth1Signer oauth1Signer,
            SecureRandom secureRandom) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.profileService = profileService;
        this.featureDefaultsService = featureDefaultsService;
        this.clientCredentialsProvider = clientCredentialsProvider;
        this.deviceProfileStore = deviceProfileStore;
        this.vaultSessionManager = vaultSessionManager;
        this.androidRequestHeaders = androidRequestHeaders;
        this.oauth1Signer = oauth1Signer;
        this.secureRandom = secureRandom;
    }

    public GraphQlResult execute(
            String accountId, String purpose, Map<String, Object> variables) {
        var profile = profileService.profile();
        var operation = profileService.requireOperation(purpose);
        var features = featureDefaultsService.selectFor(profile);
        var account = vaultSessionManager.requireAccount(accountId);
        var clientCredentials = clientCredentialsProvider.require();
        var deviceIdentity = deviceProfileStore.require().toIdentity(profile);
        var operationUri = operation.resolveAgainst(profile.graphqlBaseUri());

        String body = null;
        URI requestUri;
        if (operation.type() == OperationType.MUTATION) {
            body = writeJson(Map.of("variables", variables, "features", features));
            requestUri = operationUri;
        } else {
            requestUri = withQuery(
                    operationUri,
                    Map.of("variables", writeJson(variables), "features", writeJson(features)));
        }

        var oauthCredentials = new Credentials(
                clientCredentials.consumerKey(),
                clientCredentials.consumerSecret(),
                account.oauthToken(),
                account.oauthTokenSecret());
        var authorization = oauth1Signer.authorizationHeader(
                operation.type() == OperationType.MUTATION ? "POST" : "GET",
                requestUri,
                List.of(),
                oauthCredentials,
                Long.toString(Instant.now().getEpochSecond()),
                newNonce());
        var requestBuilder = HttpRequest.newBuilder(requestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization);
        androidRequestHeaders.apply(requestBuilder, profile, deviceIdentity);
        if (body == null) {
            requestBuilder.GET();
        } else {
            requestBuilder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }

        try {
            var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(
                        "GraphQL " + purpose + "に失敗しました。HTTP " + response.statusCode(),
                        response.statusCode());
            }
            rejectGraphQlErrors(response.body(), purpose);
            return new GraphQlResult(purpose, operation.operationName(), response.body());
        } catch (IOException exception) {
            throw new XApiHttpException("GraphQL " + purpose + "の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException("GraphQL " + purpose + "が中断されました。", exception);
        }
    }

    private void rejectGraphQlErrors(String body, String purpose) {
        try {
            var root = objectMapper.readTree(body);
            var errors = root.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                throw new XApiHttpException(
                        "GraphQL " + purpose + "がエラーを返しました。", 502);
            }
        } catch (JacksonException exception) {
            throw new XApiHttpException("GraphQL応答を解析できません。", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("GraphQL入力をJSONへ変換できません。", exception);
        }
    }

    private String newNonce() {
        var bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static URI withQuery(URI uri, Map<String, String> values) {
        var sorted = new LinkedHashMap<String, String>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        var query = String.join("&", sorted.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .toList());
        return URI.create(uri + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record GraphQlResult(String purpose, String operationName, String rawJson) {

        @Override
        public String toString() {
            return "GraphQlResult[purpose="
                    + purpose
                    + ", operationName="
                    + operationName
                    + ", rawJson=<redacted>]";
        }
    }
}
