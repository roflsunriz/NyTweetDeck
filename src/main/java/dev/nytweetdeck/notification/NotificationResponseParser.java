package dev.nytweetdeck.notification;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationResponseParser {

    private static final Pattern POST_ID_PATTERN =
            Pattern.compile(
                    "(?:status(?:es)?/|tweet(?:_id|id)[=:]|(?:tweet|post)\\?(?:[^\\s#]*&)?(?:id|tweet_id)=)([0-9]{1,24})",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTE_ID_PATTERN = Pattern.compile(
            "/i/birdwatch/n/([0-9]{1,24})", Pattern.CASE_INSENSITIVE);

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
                && (firstObject(node, "socialContext", "social_context") != null
                        || node.get("notification_icon") != null
                        || object(node, "icon") != null
                        || object(node, "message") != null
                        || object(node, "rich_message") != null);
    }

    private static NotificationPage.Notification parseNotification(JsonNode node) {
        var socialContext = firstObject(
                node, "socialContext", "social_context", "notification_social_context");
        var general = firstObject(socialContext, "generalContext", "general_context");
        var topic = firstObject(socialContext, "topicContext", "topic_context");
        var contextText = firstNonNull(text(general, "text"), text(topic, "text"));
        var messageText = text(object(node, "message"), "text");
        var richMessageText = text(firstObject(node, "rich_message", "richMessage"), "text");
        var displayText = firstNonNull(contextText, messageText);
        displayText = firstNonNull(displayText, richMessageText);
        displayText = firstNonNull(displayText, text(node, "text"));
        if (displayText == null) {
            displayText = "";
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
        collectImageUrls(node, images);
        return new NotificationPage.Notification(
                text(node, "id"),
                notificationKind(node),
                displayText,
                findNoteId(node),
                findPostId(node),
                images);
    }

    private static String notificationKind(JsonNode node) {
        var icon = firstNonNull(text(node, "notification_icon"), text(object(node, "icon"), "id"));
        var normalized = icon == null ? "" : icon.toLowerCase(Locale.ROOT);
        if (normalized.contains("heart") || normalized.contains("favorite") || normalized.contains("like")) {
            return "like";
        }
        if (normalized.contains("retweet") || normalized.contains("repost")) {
            return "repost";
        }
        if (normalized.contains("reply") || normalized.contains("mention")) {
            return "reply";
        }
        if (normalized.contains("follow") || normalized.contains("person")) {
            return "follow";
        }
        if (normalized.contains("birdwatch") || normalized.contains("community")) {
            return "community_note";
        }
        return "notification";
    }

    private static String findPostId(JsonNode node) {
        var urlNode = firstObject(node, "url", "notification_url", "notificationUrl");
        var url = firstNonNull(text(urlNode, "expanded_url"), text(urlNode, "expandedUrl"));
        url = firstNonNull(url, text(urlNode, "url"));
        if (url != null) {
            var matcher = POST_ID_PATTERN.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        var referenced = findPostReference(node);
        if (referenced != null) {
            return referenced;
        }
        return findTweetId(node);
    }

    private static String findNoteId(JsonNode node) {
        var urlNode = firstObject(node, "notification_url", "notificationUrl", "url");
        var url = firstNonNull(text(urlNode, "expanded_url"), text(urlNode, "expandedUrl"));
        url = firstNonNull(url, text(urlNode, "url"));
        if (url == null) return null;
        var matcher = NOTE_ID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String findPostReference(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            var matcher = POST_ID_PATTERN.matcher(node.asString(""));
            return matcher.find() ? matcher.group(1) : null;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                var field = property.getKey();
                var value = property.getValue();
                if ((field.equals("tweet_id")
                                || field.equals("tweetId")
                                || field.equals("tweet_id_str")
                                || field.equals("postId"))
                        && value.asString("").matches("[0-9]{1,24}")) {
                    return value.asString("");
                }
                var found = findPostReference(value);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var found = findPostReference(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String findTweetId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if ("Tweet".equals(text(node, "__typename")) && text(node, "rest_id") != null) {
                return text(node, "rest_id");
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                var found = findTweetId(property.getValue());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var found = findTweetId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectImageUrls(JsonNode node, java.util.List<String> images) {
        if (node == null || images.size() >= 4) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                var field = property.getKey().toLowerCase(Locale.ROOT);
                var value = property.getValue();
                if ((field.contains("profile_image") || field.contains("contextimage") || field.equals("image_url"))
                        && value.isString()) {
                    var imageUrl = value.asString(null);
                    if (isSafeImageUrl(imageUrl) && !images.contains(imageUrl)) {
                        images.add(imageUrl);
                    }
                } else {
                    collectImageUrls(value, images);
                }
                if (images.size() >= 4) {
                    return;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                collectImageUrls(child, images);
                if (images.size() >= 4) {
                    return;
                }
            }
        }
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

    private static JsonNode firstObject(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = object(node, field);
            if (value != null) return value;
        }
        return null;
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
