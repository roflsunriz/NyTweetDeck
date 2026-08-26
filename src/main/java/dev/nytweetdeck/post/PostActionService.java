package dev.nytweetdeck.post;

import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PostActionService {

    private final AuthenticatedGraphQlClient graphQlClient;

    public PostActionService(AuthenticatedGraphQlClient graphQlClient) {
        this.graphQlClient = graphQlClient;
    }

    public ActionResult execute(String accountId, String postId, String action) {
        var request = createRequest(postId, action);
        graphQlClient.execute(accountId, request.purpose(), request.variables());
        return new ActionResult(postId, action);
    }

    ActionRequest createRequest(String postId, String action) {
        if (postId == null || !postId.matches("[0-9]{1,30}")) {
            throw new IllegalArgumentException("ポストIDの形式が不正です。");
        }
        return switch (action) {
            case "like" -> new ActionRequest("like", Map.of("tweet_id", postId));
            case "unlike" -> new ActionRequest("unlike", Map.of("tweet_id", postId));
            case "repost" -> new ActionRequest("repost", Map.of("tweet_id", postId));
            case "undoRepost" ->
                new ActionRequest("undoRepost", Map.of("source_tweet_id", postId));
            case "bookmark" -> new ActionRequest("bookmark", Map.of("tweet_id", postId));
            case "removeBookmark" ->
                new ActionRequest("removeBookmark", Map.of("tweet_id", postId));
            default -> throw new IllegalArgumentException("未対応のポスト操作です: " + action);
        };
    }

    record ActionRequest(String purpose, Map<String, Object> variables) {}

    public record ActionResult(String postId, String action) {}
}
