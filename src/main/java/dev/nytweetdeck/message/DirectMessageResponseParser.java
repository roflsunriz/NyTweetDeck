package dev.nytweetdeck.message;

import dev.nytweetdeck.message.DirectMessagePage.DirectMessage;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DirectMessageResponseParser {

    private final ObjectMapper objectMapper;

    public DirectMessageResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DirectMessagePage parse(String body) {
        try {
            var root = objectMapper.readTree(body);
            var state = object(root, "inbox_initial_state");
            if (state == null) {
                state = root;
            }
            var users = parseUsers(object(state, "users"));
            var messages = new ArrayList<DirectMessage>();
            var entries = state.get("entries");
            if (entries != null && entries.isArray()) {
                for (var entry : entries) {
                    var message = object(entry, "message");
                    if (message == null) {
                        continue;
                    }
                    var data = object(message, "message_data");
                    var id = text(message, "id");
                    var senderId = text(data, "sender_id");
                    var messageText = text(data, "text");
                    if (id == null || senderId == null || messageText == null) {
                        continue;
                    }
                    var user = users.getOrDefault(senderId, User.EMPTY);
                    messages.add(new DirectMessage(
                            id,
                            text(message, "conversation_id"),
                            senderId,
                            user.name(),
                            user.username(),
                            user.avatarUrl(),
                            messageText,
                            longValue(message, "time")));
                }
            }
            messages.sort(Comparator.comparingLong(DirectMessage::timestamp).reversed());
            return new DirectMessagePage(
                    messages, firstNonNull(text(state, "cursor"), text(root, "cursor")));
        } catch (JacksonException exception) {
            throw new XApiHttpException("ダイレクトメッセージ応答を解析できません。", exception);
        }
    }

    private static Map<String, User> parseUsers(JsonNode usersNode) {
        var users = new LinkedHashMap<String, User>();
        if (usersNode == null || !usersNode.isObject()) {
            return users;
        }
        for (Map.Entry<String, JsonNode> property : usersNode.properties()) {
            var value = property.getValue();
            users.put(
                    property.getKey(),
                    new User(
                            text(value, "name"),
                            text(value, "screen_name"),
                            text(value, "profile_image_url_https")));
        }
        return users;
    }

    private static JsonNode object(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private static long longValue(JsonNode node, String field) {
        if (node == null || node.get(field) == null) {
            return 0;
        }
        return node.get(field).asLong(0);
    }

    private static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }

    private record User(String name, String username, String avatarUrl) {
        private static final User EMPTY = new User(null, null, null);
    }
}
