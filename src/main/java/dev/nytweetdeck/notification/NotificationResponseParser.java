package dev.nytweetdeck.notification;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationResponseParser {

    private final ObjectMapper objectMapper;

    public NotificationResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public java.util.List<NotificationPage.Notification> parse(String body) {
        try {
            var root = objectMapper.readTree(body);
            var notifications = new LinkedHashMap<String, NotificationPage.Notification>();
            visit(root, notifications);
            return new ArrayList<>(notifications.values());
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("通知応答を解析できません。", exception);
        }
    }

    private void visit(
            JsonNode node, Map<String, NotificationPage.Notification> notifications) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (var child : node) {
                visit(child, notifications);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (isNotification(node)) {
            var notification = parseNotification(node);
            notifications.putIfAbsent(notification.id(), notification);
            return;
        }
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            visit(property.getValue(), notifications);
        }
    }

    private static boolean isNotification(JsonNode node) {
        return text(node, "id") != null
                && object(node, "url") != null
                && firstObject(node, "socialContext", "social_context") != null;
    }

    private static NotificationPage.Notification parseNotification(JsonNode node) {
        var socialContext = firstObject(node, "socialContext", "social_context");
        var general = firstObject(socialContext, "generalContext", "general_context");
        var topic = firstObject(socialContext, "topicContext", "topic_context");
        var text = firstNonNull(text(general, "text"), text(topic, "text"));
        if (text == null) {
            text = "";
        }
        var images = new ArrayList<String>();
        var imageNodes = general == null ? null : general.get("contextImageUrls");
        if (imageNodes != null && imageNodes.isArray()) {
            for (var imageNode : imageNodes) {
                var imageUrl = imageNode.asString(null);
                if (isSafeImageUrl(imageUrl)) {
                    images.add(imageUrl);
                }
            }
        }
        var urlNode = object(node, "url");
        var url = firstNonNull(text(urlNode, "expanded_url"), text(urlNode, "expandedUrl"));
        url = firstNonNull(url, text(urlNode, "url"));
        return new NotificationPage.Notification(
                text(node, "id"), text, safeXUrl(url), images);
    }

    private static String safeXUrl(String value) {
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
                // Fall through to the notifications page.
            }
        }
        return "https://x.com/notifications";
    }

    private static boolean isSafeImageUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            var uri = URI.create(value);
            var host = uri.getHost();
            var normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && (normalizedHost.equals("twimg.com")
                            || normalizedHost.endsWith(".twimg.com"));
        } catch (IllegalArgumentException exception) {
            return false;
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
