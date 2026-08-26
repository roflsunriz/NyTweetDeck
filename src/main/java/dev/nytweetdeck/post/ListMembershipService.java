package dev.nytweetdeck.post;

import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ListMembershipService {

    private final AuthenticatedGraphQlClient graphQlClient;

    public ListMembershipService(AuthenticatedGraphQlClient graphQlClient) {
        this.graphQlClient = graphQlClient;
    }

    public MembershipResult execute(
            String accountId, String userId, String listId, String action) {
        var request = createRequest(userId, listId, action);
        graphQlClient.execute(accountId, request.purpose(), request.variables());
        return new MembershipResult(userId, listId, action);
    }

    ActionRequest createRequest(String userId, String listId, String action) {
        validateId(userId, "ユーザー");
        validateId(listId, "リスト");
        var purpose = switch (action) {
            case "add" -> "listMemberAdd";
            case "remove" -> "listMemberRemove";
            default -> throw new IllegalArgumentException("未対応のリスト操作です: " + action);
        };
        return new ActionRequest(
                purpose, Map.of("list_id", listId, "user_id", userId));
    }

    private static void validateId(String value, String label) {
        if (value == null || !value.matches("[0-9]{1,30}")) {
            throw new IllegalArgumentException(label + "IDの形式が不正です。");
        }
    }

    record ActionRequest(String purpose, Map<String, Object> variables) {}

    public record MembershipResult(String userId, String listId, String action) {}
}
