package dev.nytweetdeck.list;

import dev.nytweetdeck.list.ListDirectoryPage.ListOption;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ListDirectoryParser {

    private final ObjectMapper objectMapper;

    public ListDirectoryParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ListDirectoryPage parse(String body, String source) {
        try {
            var root = objectMapper.readTree(body);
            var lists = new LinkedHashMap<String, ListOption>();
            var cursor = new String[1];
            visit(root, source, lists, cursor);
            return new ListDirectoryPage(new ArrayList<>(lists.values()), cursor[0]);
        } catch (JacksonException exception) {
            throw new XApiHttpException("Xリスト一覧を解析できません。", exception);
        }
    }

    private static void visit(
            JsonNode node, String source, Map<String, ListOption> lists, String[] cursor) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (var child : node) {
                visit(child, source, lists, cursor);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        var cursorType = firstNonNull(text(node, "cursorType"), text(node, "cursor_type"));
        if ("Bottom".equalsIgnoreCase(cursorType)) {
            cursor[0] = text(node, "value");
        }
        if ("List".equals(text(node, "__typename"))
                && text(node, "rest_id") != null
                && text(node, "name") != null) {
            var owner = node.path("user_results").path("result");
            var ownerCore = owner.path("core");
            lists.putIfAbsent(
                    text(node, "rest_id"),
                    new ListOption(
                            text(node, "rest_id"),
                            text(node, "name"),
                            text(node, "description"),
                            text(ownerCore, "name"),
                            text(ownerCore, "screen_name"),
                            number(node, "member_count"),
                            number(node, "subscriber_count"),
                            source));
            return;
        }
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            visit(property.getValue(), source, lists, cursor);
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private static long number(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null ? 0 : value.asLong(0);
    }

    private static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }
}
