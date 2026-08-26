package dev.nytweetdeck.user;

import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class UserDirectoryService {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final ObjectMapper objectMapper;

    public UserDirectoryService(
            AuthenticatedGraphQlClient graphQlClient, ObjectMapper objectMapper) {
        this.graphQlClient = graphQlClient;
        this.objectMapper = objectMapper;
    }

    public UserOption resolve(String accountId, String input) {
        var username = input == null ? "" : input.strip().replaceFirst("^@", "");
        if (!username.matches("[A-Za-z0-9_]{1,15}")) {
            throw new IllegalArgumentException("有効なXユーザー名を入力してください。");
        }
        var result = graphQlClient.execute(
                accountId,
                "userByScreenName",
                Map.of("screen_name", username, "withGrokTranslatedBio", false));
        try {
            var user = findUser(objectMapper.readTree(result.rawJson()));
            if (user == null) {
                throw new XApiHttpException("Xユーザーが見つかりません。", 404);
            }
            var core = user.get("core");
            var avatar = user.get("avatar");
            return new UserOption(
                    text(user, "rest_id"),
                    text(core, "screen_name"),
                    text(core, "name"),
                    text(avatar, "image_url"));
        } catch (JacksonException exception) {
            throw new XApiHttpException("Xユーザー情報を解析できません。", exception);
        }
    }

    private static JsonNode findUser(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if ("User".equals(text(node, "__typename")) && text(node, "rest_id") != null) {
                return node;
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                var found = findUser(property.getValue());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var found = findUser(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    public record UserOption(String id, String username, String displayName, String avatarUrl) {}
}
