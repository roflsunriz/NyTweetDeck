package dev.nytweetdeck.post;

import dev.nytweetdeck.timeline.TimelineEventBus;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class UserActionService {

    private final AuthenticatedRestClient restClient;
    private final TimelineEventBus eventBus;

    public UserActionService(AuthenticatedRestClient restClient, TimelineEventBus eventBus) {
        this.restClient = restClient;
        this.eventBus = eventBus;
    }

    public UserActionResult execute(String accountId, String userId, String action) {
        var request = createRequest(userId, action);
        restClient.postForm(accountId, request.endpoint(), request.parameters());
        if (action.equals("mute") || action.equals("block")) {
            eventBus.publishUserSuppression(accountId, action, userId);
        }
        return new UserActionResult(userId, action);
    }

    ActionRequest createRequest(String userId, String action) {
        if (userId == null || !userId.matches("[0-9]{1,30}")) {
            throw new IllegalArgumentException("ユーザーIDの形式が不正です。");
        }
        var endpoint = switch (action) {
            case "follow" -> "followUser";
            case "mute" -> "muteUser";
            case "block" -> "blockUser";
            default -> throw new IllegalArgumentException("未対応のユーザー操作です: " + action);
        };
        return new ActionRequest(endpoint, Map.of("user_id", userId));
    }

    record ActionRequest(String endpoint, Map<String, String> parameters) {}

    public record UserActionResult(String userId, String action) {}
}
