package dev.nytweetdeck.trend;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TrendResponseParser {

    private final ObjectMapper objectMapper;

    public TrendResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TrendPage parse(String body) {
        try {
            var root = objectMapper.readTree(body);
            var trends = new LinkedHashMap<String, TrendPage.Trend>();
            var cursor = new String[1];
            visit(root, trends, cursor);
            return new TrendPage(new ArrayList<>(trends.values()), cursor[0]);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("トレンド応答を解析できません。", exception);
        }
    }

    private void visit(
            JsonNode node, Map<String, TrendPage.Trend> trends, String[] cursor) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (var child : node) {
                visit(child, trends, cursor);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        findCursor(node, cursor);
        if (isTrend(node)) {
            var trend = parseTrend(node);
            trends.putIfAbsent(trend.name(), trend);
            return;
        }
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            visit(property.getValue(), trends, cursor);
        }
    }

    private static boolean isTrend(JsonNode node) {
        return text(node, "name") != null
                && object(node, "url") != null
                && (node.get("trendMetadata") != null
                        || node.get("trend_metadata") != null
                        || node.get("rank") != null
                        || node.get("groupedTrends") != null
                        || node.get("grouped_trends") != null);
    }

    private static TrendPage.Trend parseTrend(JsonNode node) {
        var name = text(node, "name");
        var metadata = firstObject(node, "trendMetadata", "trend_metadata");
        var urlNode = object(node, "url");
        var url = firstNonNull(text(urlNode, "expanded_url"), text(urlNode, "expandedUrl"));
        url = firstNonNull(url, text(urlNode, "url"));
        return new TrendPage.Trend(
                name,
                text(node, "description"),
                text(node, "rank"),
                safeUrl(url, name),
                firstNonNull(text(metadata, "domain_context"), text(metadata, "domainContext")),
                firstNonNull(text(metadata, "metaDescription"), text(metadata, "meta_description")));
    }

    private static String safeUrl(String value, String name) {
        if (value != null) {
            try {
                var uri = URI.create(value);
                var host = uri.getHost();
                var normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
                if ("https".equalsIgnoreCase(uri.getScheme())
                        && (normalizedHost.equals("x.com")
                                || normalizedHost.endsWith(".x.com")
                                || normalizedHost.equals("twitter.com")
                                || normalizedHost.endsWith(".twitter.com"))) {
                    return value;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to a safe search URL.
            }
        }
        return "https://x.com/search?q="
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void findCursor(JsonNode node, String[] cursor) {
        var cursorType = firstNonNull(text(node, "cursorType"), text(node, "cursor_type"));
        if ("Bottom".equalsIgnoreCase(cursorType)) {
            cursor[0] = text(node, "value");
        }
    }

    private static JsonNode firstObject(JsonNode node, String first, String second) {
        var value = object(node, first);
        return value == null ? object(node, second) : value;
    }

    private static JsonNode object(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }
}
