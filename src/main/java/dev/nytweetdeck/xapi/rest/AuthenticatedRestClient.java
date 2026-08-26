package dev.nytweetdeck.xapi.rest;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import dev.nytweetdeck.xapi.http.WebSessionRequestHeaders;
import dev.nytweetdeck.xapi.profile.XApiProfileService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedRestClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final XApiProfileService profileService;
    private final AccountVaultSessionManager vaultSessionManager;

    public AuthenticatedRestClient(
            HttpClient httpClient,
            XApiProfileService profileService,
            AccountVaultSessionManager vaultSessionManager) {
        this.httpClient = httpClient;
        this.profileService = profileService;
        this.vaultSessionManager = vaultSessionManager;
    }

    public RestResult get(String accountId, String endpointName, Map<String, String> parameters) {
        var profile = profileService.profile();
        var path = profile.restEndpoints().get(endpointName);
        if (path == null) {
            throw new IllegalArgumentException("未定義のRESTエンドポイントです: " + endpointName);
        }
        var account = vaultSessionManager.requireAccount(accountId);
        var baseUri = URI.create("https://api.x.com");
        var requestUri = withQuery(baseUri.resolve(path), parameters);
        var requestBuilder = HttpRequest.newBuilder(requestUri).timeout(REQUEST_TIMEOUT).GET();
        applyAuthentication(requestBuilder, requestUri, "GET", List.of(), account);
        return send(requestBuilder.build(), endpointName);
    }

    public RestResult postForm(
            String accountId, String endpointName, Map<String, String> parameters) {
        var profile = profileService.profile();
        var path = profile.restEndpoints().get(endpointName);
        if (path == null) {
            throw new IllegalArgumentException("未定義のRESTエンドポイントです: " + endpointName);
        }
        var account = vaultSessionManager.requireAccount(accountId);
        var baseUri = URI.create("https://api.x.com");
        var requestUri = baseUri.resolve(path);
        var bodyParameters = parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Parameter(entry.getKey(), entry.getValue()))
                .toList();
        var body = formBody(bodyParameters);
        var requestBuilder = HttpRequest.newBuilder(requestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        applyAuthentication(requestBuilder, requestUri, "POST", bodyParameters, account);
        return send(requestBuilder.build(), endpointName);
    }

    private void applyAuthentication(
            HttpRequest.Builder requestBuilder,
            URI requestUri,
            String method,
            List<Parameter> bodyParameters,
            dev.nytweetdeck.account.vault.AccountSecrets account) {
        WebSessionRequestHeaders.apply(requestBuilder, account, "ja");
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

    public record Parameter(String name, String value) {}
}
