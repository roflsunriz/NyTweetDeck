package dev.nytweetdeck.xapi.graphql;

import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.http.WebSessionRequestHeaders;
import dev.nytweetdeck.xapi.http.XClientTransactionIdService;
import dev.nytweetdeck.xapi.profile.XApiProfile;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthenticatedGraphQlClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern LANGUAGE_PATTERN =
            Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*");
    private static final Set<String> POST_QUERY_PURPOSES = Set.of("search");
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
    private final AccountStore accountStore;
    private final XClientTransactionIdService transactionIdService;

    @Autowired
    public AuthenticatedGraphQlClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            XApiProfileService profileService,
            AccountStore accountStore,
            XClientTransactionIdService transactionIdService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.profileService = profileService;
        this.accountStore = accountStore;
        this.transactionIdService = transactionIdService;
    }

    public AuthenticatedGraphQlClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            XApiProfileService profileService,
            AccountStore accountStore) {
        this(httpClient, objectMapper, profileService, accountStore, null);
    }

    public GraphQlResult execute(
            String accountId, String purpose, Map<String, Object> variables) {
        return execute(accountId, purpose, variables, "ja");
    }

    public GraphQlResult execute(
            String accountId,
            String purpose,
            Map<String, Object> variables,
            String language) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var prepared = prepareRequest(accountId, purpose, variables, language);
            try {
                var response = httpClient.send(
                        prepared.request(), HttpResponse.BodyHandlers.ofString());
                if (shouldRefreshTransaction(prepared, response, attempt)) {
                    transactionIdService.invalidate();
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new XApiHttpException(
                            "GraphQL " + purpose + "に失敗しました。HTTP " + response.statusCode(),
                            response.statusCode());
                }
                rejectGraphQlErrors(response.body(), purpose);
                return new GraphQlResult(
                        purpose, prepared.operation().operationName(), response.body());
            } catch (IOException exception) {
                throw new XApiHttpException("GraphQL " + purpose + "の通信に失敗しました。", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new XApiHttpException("GraphQL " + purpose + "が中断されました。", exception);
            }
        }
        throw new XApiHttpException("GraphQL " + purpose + "のWeb署名を更新できませんでした。", 502);
    }

    PreparedRequest prepareRequest(
            String accountId, String purpose, Map<String, Object> variables) {
        return prepareRequest(accountId, purpose, variables, "ja");
    }

    PreparedRequest prepareRequest(
            String accountId,
            String purpose,
            Map<String, Object> variables,
            String language) {
        var profile = profileService.profile();
        var operation = profileService.requireOperation(purpose);
        var features = profileService.selectFeatures(operation);
        var account = accountStore.requireAccount(accountId);
        var operationUri = operation.resolveAgainst(profile.graphqlBaseUri());

        var fieldToggles = operation.fieldToggles().isEmpty()
                ? DEFAULT_FIELD_TOGGLES
                : operation.fieldToggles().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                key -> key, key -> false));
        String body = null;
        URI requestUri;
        if (operation.type() == OperationType.MUTATION) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("variables", variables);
            payload.put("queryId", operation.operationId());
            if (!operation.featureKeys().isEmpty()) {
                payload.put("features", features);
            }
            body = writeJson(payload);
            requestUri = operationUri;
        } else if (POST_QUERY_PURPOSES.contains(purpose)) {
            body = postQueryBody(variables, features, fieldToggles);
            requestUri = operationUri;
        } else {
            requestUri = withQuery(
                    operationUri,
                    Map.of(
                            "variables", writeJson(variables),
                            "features", writeJson(features),
                            "fieldToggles", writeJson(fieldToggles)));
        }

        var requestBuilder = HttpRequest.newBuilder(requestUri).timeout(REQUEST_TIMEOUT);
        WebSessionRequestHeaders.apply(requestBuilder, account, normalizeLanguage(language));
        if (body == null) {
            requestBuilder.GET();
        } else {
            requestBuilder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        if (operation.type() == OperationType.MUTATION) {
            if (transactionIdService == null) {
                throw new IllegalStateException("X Web署名サービスが接続されていません。");
            }
            requestBuilder.header(
                    "X-Client-Transaction-Id",
                    transactionIdService.generate("POST", requestUri));
        }
        return new PreparedRequest(requestBuilder.build(), operation);
    }

    private boolean shouldRefreshTransaction(
            PreparedRequest prepared,
            HttpResponse<String> response,
            int attempt) {
        return attempt == 0
                && transactionIdService != null
                && prepared.operation().type() == OperationType.MUTATION
                && (response.statusCode() == 404 || hasGraphQlErrorCode(response.body(), 344));
    }

    private boolean hasGraphQlErrorCode(String body, int expectedCode) {
        try {
            var errors = objectMapper.readTree(body).get("errors");
            if (errors == null || !errors.isArray()) {
                return false;
            }
            for (var error : errors) {
                var code = error.get("code");
                if (code != null && code.canConvertToInt() && code.asInt() == expectedCode) {
                    return true;
                }
            }
            return false;
        } catch (JacksonException exception) {
            return false;
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

    private String postQueryBody(
            Map<String, Object> variables,
            Map<String, Boolean> features,
            Map<String, Boolean> fieldToggles) {
        return writeJson(Map.of(
                "variables", variables,
                "features", features,
                "fieldToggles", fieldToggles));
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

    private static String normalizeLanguage(String language) {
        var normalized = language == null ? "" : language.strip().replace('_', '-');
        if (!LANGUAGE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("表示言語の形式が不正です。");
        }
        return normalized.toLowerCase(Locale.ROOT);
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

    record PreparedRequest(HttpRequest request, XApiProfile.GraphQlOperation operation) {}
}
