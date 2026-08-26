package dev.nytweetdeck.xapi.graphql;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.http.WebSessionRequestHeaders;
import dev.nytweetdeck.xapi.profile.XApiProfile.OperationType;
import dev.nytweetdeck.xapi.profile.XApiProfileService;
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
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthenticatedGraphQlClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Map<String, Boolean> DEFAULT_FIELD_TOGGLES = Map.of(
            "withPayments", false,
            "withAuxiliaryUserLabels", false,
            "withArticleRichContentState", false,
            "withArticlePlainText", false,
            "withArticleSummaryText", false,
            "withArticleVoiceOver", false,
            "withGrokAnalyze", false,
            "withDisallowedReplyControls", false);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final XApiProfileService profileService;
    private final AccountVaultSessionManager vaultSessionManager;

    public AuthenticatedGraphQlClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            XApiProfileService profileService,
            AccountVaultSessionManager vaultSessionManager) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.profileService = profileService;
        this.vaultSessionManager = vaultSessionManager;
    }

    public GraphQlResult execute(
            String accountId, String purpose, Map<String, Object> variables) {
        var profile = profileService.profile();
        var operation = profileService.requireOperation(purpose);
        var features = profileService.selectFeatures(operation);
        var account = vaultSessionManager.requireAccount(accountId);
        var operationUri = operation.resolveAgainst(profile.graphqlBaseUri());

        String body = null;
        URI requestUri;
        if (operation.type() == OperationType.MUTATION) {
            body = writeJson(Map.of("variables", variables, "features", features));
            requestUri = operationUri;
        } else {
            var fieldToggles = operation.fieldToggles().isEmpty()
                    ? DEFAULT_FIELD_TOGGLES
                    : operation.fieldToggles().stream()
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                    key -> key, key -> false));
            requestUri = withQuery(
                    operationUri,
                    Map.of(
                            "variables", writeJson(variables),
                            "features", writeJson(features),
                            "fieldToggles", writeJson(fieldToggles)));
        }

        var requestBuilder = HttpRequest.newBuilder(requestUri).timeout(REQUEST_TIMEOUT);
        WebSessionRequestHeaders.apply(requestBuilder, account, "ja");
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
            var data = root.get("data");
            if (errors != null
                    && errors.isArray()
                    && !errors.isEmpty()
                    && (data == null || data.isNull() || data.isEmpty())) {
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
