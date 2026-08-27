package dev.nytweetdeck.xapi.profile;

import dev.nytweetdeck.xapi.auth.browser.WebBearerTokenProvider;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class XWebMetadataResolver {

    private static final URI WEB_HOME = URI.create("https://x.com/home");
    private static final URI ASSET_BASE =
            URI.create("https://abs.twimg.com/responsive-web/client-web/");
    private static final int MAX_ASSET_LENGTH = 8_000_000;
    private static final Pattern SCRIPT_URL = Pattern.compile(
            "https://abs\\.twimg\\.com/responsive-web/client-web/[^\\\"'<> ]+\\.js");
    private static final Pattern MANIFEST_ENTRY =
            Pattern.compile("(?:^|[,{])(\\d+):\\\"([^\\\"]+)\\\"");
    private static final Pattern BOOLEAN_FEATURE = Pattern.compile(
            "\\\"([^\\\"]+)\\\":\\{\\\"value\\\":(true|false)");
    private static final Pattern OPERATION = Pattern.compile(
            "queryId:\\s*\"([^\"]+)\"\\s*,\\s*operationName:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*operationType:\\s*\"(query|mutation)\"\\s*,\\s*metadata:\\s*\\{"
                    + "\\s*featureSwitches:\\s*\\[([^]]*)]\\s*,\\s*fieldToggles:\\s*\\[([^]]*)]\\s*}");
    private static final Pattern QUOTED_VALUE = Pattern.compile("\\\"([^\\\"]+)\\\"");
    private static final Pattern SAFE_OPERATION_ID = Pattern.compile("[A-Za-z0-9_-]{8,100}");
    private static final List<String> RELEVANT_CHUNK_MARKERS = List.of(
            "loggedinmain",
            "hometimeline",
            "notifications",
            "bookmark",
            "history",
            "explore",
            "userprofile",
            "conversation",
            "birdwatch",
            "search",
            "tweet");

    private final HttpClient httpClient;

    public XWebMetadataResolver(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public ResolvedMetadata resolve(Set<String> requiredOperationNames) {
        var html = fetch(WEB_HOME);
        var defaultsFromPage = parseBooleanFeatures(html);
        var operations = new LinkedHashMap<String, ResolvedOperation>();
        var directScripts = parseScriptUrls(html);
        String sourceVersion = "unknown";
        for (var script : directScripts) {
            var body = fetch(script);
            parseOperations(body).forEach(operations::putIfAbsent);
            var file = script.getPath().substring(script.getPath().lastIndexOf('/') + 1);
            if (file.startsWith("main.")) {
                sourceVersion = file;
            }
        }

        var candidates = parseChunkCandidates(html).stream()
                .filter(candidate -> isRelevant(candidate.name()))
                .sorted(Comparator.comparingInt(XWebMetadataResolver::priority))
                .toList();
        for (var candidate : candidates) {
            if (operations.keySet().containsAll(requiredOperationNames)) {
                break;
            }
            var body = fetchChunk(candidate);
            if (body != null) {
                parseOperations(body).forEach(operations::putIfAbsent);
            }
        }

        var missing = new LinkedHashSet<>(requiredOperationNames);
        missing.removeAll(operations.keySet());
        if (!missing.isEmpty()) {
            throw new XApiHttpException(
                    "X公式Web資産に必須operationがありません: " + String.join(", ", missing),
                    502);
        }

        var selectedOperations = new LinkedHashMap<String, ResolvedOperation>();
        var allFeatureKeys = new LinkedHashSet<String>();
        for (var name : requiredOperationNames) {
            var operation = operations.get(name);
            selectedOperations.put(name, operation);
            allFeatureKeys.addAll(operation.featureKeys());
        }
        var defaults = new LinkedHashMap<String, Boolean>();
        for (var key : allFeatureKeys) {
            defaults.put(key, defaultsFromPage.getOrDefault(key, false));
        }
        return new ResolvedMetadata(
                sourceVersion,
                Map.copyOf(selectedOperations),
                List.copyOf(allFeatureKeys),
                Map.copyOf(defaults));
    }

    Map<String, ResolvedOperation> parseOperations(String javascript) {
        var operations = new LinkedHashMap<String, ResolvedOperation>();
        var matcher = OPERATION.matcher(javascript);
        while (matcher.find()) {
            var id = matcher.group(1);
            var name = matcher.group(2);
            if (!SAFE_OPERATION_ID.matcher(id).matches()
                    || !name.matches("[A-Za-z][A-Za-z0-9_]{1,100}")) {
                continue;
            }
            var type = "mutation".equals(matcher.group(3))
                    ? XApiProfile.OperationType.MUTATION
                    : XApiProfile.OperationType.QUERY;
            operations.put(
                    name,
                    new ResolvedOperation(
                            id,
                            name,
                            type,
                            parseQuotedList(matcher.group(4)),
                            parseQuotedList(matcher.group(5))));
        }
        return operations;
    }

    Map<String, Boolean> parseBooleanFeatures(String html) {
        var values = new LinkedHashMap<String, Boolean>();
        var matcher = BOOLEAN_FEATURE.matcher(html);
        while (matcher.find()) {
            values.put(matcher.group(1), Boolean.parseBoolean(matcher.group(2)));
        }
        return values;
    }

    List<ChunkCandidate> parseChunkCandidates(String html) {
        var names = new HashMap<String, String>();
        var hashes = new HashMap<String, String>();
        var matcher = MANIFEST_ENTRY.matcher(html);
        while (matcher.find()) {
            var id = matcher.group(1);
            var value = matcher.group(2);
            if (value.matches("[0-9a-f]{16}")) {
                hashes.put(id, value);
            } else {
                names.putIfAbsent(id, value);
            }
        }
        var candidates = new ArrayList<ChunkCandidate>();
        for (var entry : names.entrySet()) {
            var hash = hashes.get(entry.getKey());
            if (hash != null && safeChunkName(entry.getValue())) {
                candidates.add(new ChunkCandidate(entry.getValue(), hash));
            }
        }
        return candidates;
    }

    private String fetchChunk(ChunkCandidate candidate) {
        for (var suffix : List.of("a.js", ".js")) {
            var uri = ASSET_BASE.resolve(candidate.name() + "." + candidate.hash() + suffix);
            try {
                return fetch(uri);
            } catch (XApiHttpException exception) {
                if (exception.statusCode() != 404) {
                    throw exception;
                }
            }
        }
        return null;
    }

    private String fetch(URI uri) {
        requireOfficialUri(uri);
        var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", WebBearerTokenProvider.BROWSER_USER_AGENT)
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new XApiHttpException(
                        "X公式Web資産の取得に失敗しました。HTTP " + response.statusCode(),
                        response.statusCode());
            }
            if (response.body().length() > MAX_ASSET_LENGTH) {
                throw new XApiHttpException("X公式Web資産が上限サイズを超えています。", 502);
            }
            return response.body();
        } catch (IOException exception) {
            throw new XApiHttpException("X公式Web資産の通信に失敗しました。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XApiHttpException("X公式Web資産の取得が中断されました。", exception);
        }
    }

    private static List<URI> parseScriptUrls(String html) {
        var urls = new LinkedHashSet<URI>();
        var matcher = SCRIPT_URL.matcher(html);
        while (matcher.find()) {
            var uri = URI.create(matcher.group());
            requireOfficialUri(uri);
            urls.add(uri);
        }
        if (urls.isEmpty()) {
            throw new XApiHttpException("X公式WebのJavaScript資産が見つかりません。", 502);
        }
        return List.copyOf(urls);
    }

    private static List<String> parseQuotedList(String value) {
        var result = new ArrayList<String>();
        var matcher = QUOTED_VALUE.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return List.copyOf(result);
    }

    private static boolean isRelevant(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        return RELEVANT_CHUNK_MARKERS.stream().anyMatch(normalized::contains);
    }

    private static int priority(ChunkCandidate candidate) {
        var name = candidate.name().toLowerCase(Locale.ROOT);
        if (name.equals("bundle.loggedinmain")) return 0;
        if (name.contains("hometimeline") || name.contains("notifications")) return 1;
        if (name.contains("bookmark") || name.contains("history")) return 2;
        return 3;
    }

    private static boolean safeChunkName(String value) {
        return value.matches("[A-Za-z0-9_./~-]{1,220}") && !value.contains("..");
    }

    private static void requireOfficialUri(URI uri) {
        var host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !("x.com".equalsIgnoreCase(host)
                        || "abs.twimg.com".equalsIgnoreCase(host))) {
            throw new IllegalArgumentException("X公式Web以外の資産URLは利用できません。");
        }
    }

    public record ResolvedOperation(
            String operationId,
            String operationName,
            XApiProfile.OperationType type,
            List<String> featureKeys,
            List<String> fieldToggles) {}

    public record ResolvedMetadata(
            String sourceVersion,
            Map<String, ResolvedOperation> operationsByName,
            List<String> allFeatureKeys,
            Map<String, Boolean> featureDefaults) {}

    record ChunkCandidate(String name, String hash) {}
}
