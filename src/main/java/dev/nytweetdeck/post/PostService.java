package dev.nytweetdeck.post;

import dev.nytweetdeck.timeline.TimelinePage;
import dev.nytweetdeck.timeline.TimelineEventBus;
import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PostService {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final TimelineResponseParser responseParser;
    private final TimelineEventBus eventBus;

    public PostService(
            AuthenticatedGraphQlClient graphQlClient,
            TimelineResponseParser responseParser,
            TimelineEventBus eventBus) {
        this.graphQlClient = graphQlClient;
        this.responseParser = responseParser;
        this.eventBus = eventBus;
    }

    public PostDetail detail(String accountId, String postId, String cursor) {
        validatePostId(postId);
        var detailVariables = new LinkedHashMap<String, Object>();
        detailVariables.put("tweetId", postId);
        detailVariables.put("withCommunity", false);
        detailVariables.put("includePromotedContent", false);
        detailVariables.put("withVoice", false);
        var postResult = graphQlClient.execute(accountId, "postDetail", detailVariables);
        var postPage = responseParser.parse(postResult.rawJson());

        var conversationVariables = new LinkedHashMap<String, Object>();
        conversationVariables.put("focalTweetId", postId);
        conversationVariables.put("isReaderMode", false);
        conversationVariables.put("rankingMode", "Relevance");
        conversationVariables.put("includePromotedContent", false);
        conversationVariables.put("withCommunity", true);
        conversationVariables.put("withQuickPromoteEligibilityTweetFields", false);
        conversationVariables.put("withBirdwatchNotes", true);
        conversationVariables.put("withVoice", true);
        if (cursor != null && !cursor.isBlank()) {
            conversationVariables.put("cursor", cursor);
        }
        var conversationResult =
                graphQlClient.execute(accountId, "conversation", conversationVariables);
        var conversationPage = responseParser.parse(conversationResult.rawJson());
        var focal = postPage.posts().stream()
                .filter(post -> post.id().equals(postId))
                .findFirst()
                .or(() -> conversationPage.posts().stream()
                        .filter(post -> post.id().equals(postId))
                        .findFirst())
                .orElseThrow(() -> new IllegalStateException("ポスト詳細応答に対象ポストがありません。"));
        var replies = conversationPage.posts().stream()
                .filter(post -> !post.id().equals(postId))
                .toList();
        return new PostDetail(focal, replies, conversationPage.nextCursor());
    }

    public TimelinePage.Post create(
            String accountId, String text, String inReplyToPostId, String quotePostId) {
        var variables = createVariables(text, inReplyToPostId, quotePostId);
        var result = graphQlClient.execute(accountId, "createPost", variables);
        var post = responseParser.parse(result.rawJson()).posts().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("投稿応答に作成済みポストがありません。"));
        var reason = inReplyToPostId != null
                ? "reply"
                : quotePostId != null ? "quote" : "create";
        eventBus.publish(accountId, reason, post.id());
        return post;
    }

    Map<String, Object> createVariables(String text, String inReplyToPostId, String quotePostId) {
        var normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isEmpty() || normalizedText.length() > 4000) {
            throw new IllegalArgumentException("ポスト本文は1〜4000文字で指定してください。");
        }
        var variables = new LinkedHashMap<String, Object>();
        variables.put("tweet_text", normalizedText);
        variables.put("nullcast", false);
        variables.put("includeCommunityTweetRelationship", false);
        variables.put("includeTweetVisibilityNudge", true);
        if (inReplyToPostId != null && !inReplyToPostId.isBlank()) {
            validatePostId(inReplyToPostId);
            variables.put(
                    "reply",
                    Map.of(
                            "in_reply_to_tweet_id", inReplyToPostId,
                            "exclude_reply_user_ids", List.of()));
        }
        if (quotePostId != null && !quotePostId.isBlank()) {
            validatePostId(quotePostId);
            variables.put("attachment_url", "https://twitter.com/i/status/" + quotePostId);
        }
        return Map.copyOf(variables);
    }

    static void validatePostId(String postId) {
        if (postId == null || !postId.matches("[0-9]{1,19}")) {
            throw new IllegalArgumentException("ポストIDの形式が不正です。");
        }
    }

    public record PostDetail(
            TimelinePage.Post post, List<TimelinePage.Post> replies, String nextCursor) {
        public PostDetail {
            replies = List.copyOf(replies);
        }
    }
}
