package dev.nytweetdeck.post;

import dev.nytweetdeck.timeline.TimelinePage;
import dev.nytweetdeck.timeline.TimelineEventBus;
import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        return detail(accountId, postId, cursor, "ja", "relevance");
    }

    public PostDetail detail(
            String accountId, String postId, String cursor, String language) {
        return detail(accountId, postId, cursor, language, "relevance");
    }

    public PostDetail detail(
            String accountId,
            String postId,
            String cursor,
            String language,
            String replySort) {
        validatePostId(postId);
        var detailVariables = new LinkedHashMap<String, Object>();
        detailVariables.put("tweetId", postId);
        detailVariables.put("withCommunity", false);
        detailVariables.put("includePromotedContent", false);
        detailVariables.put("withVoice", false);
        var postResult = graphQlClient.execute(
                accountId, "postDetail", detailVariables, language);
        var postPage = responseParser.parse(postResult.rawJson());

        var conversationVariables = conversationVariables(postId, cursor, replySort);
        var conversationResult = graphQlClient.execute(
                accountId, "conversation", conversationVariables, language);
        var conversationPage = responseParser.parseConversation(conversationResult.rawJson());
        var focal = postPage.posts().stream()
                .filter(post -> post.id().equals(postId))
                .findFirst()
                .or(() -> conversationPage.posts().stream()
                        .filter(post -> post.id().equals(postId))
                        .findFirst())
                .orElseThrow(() -> new IllegalStateException("ポスト詳細応答に対象ポストがありません。"));
        var contextPosts = cursor == null || cursor.isBlank()
                ? loadConversationContext(accountId, focal, language, replySort)
                : List.<TimelinePage.Post>of();
        var contextIds = contextPosts.stream()
                .map(TimelinePage.Post::id)
                .collect(java.util.stream.Collectors.toSet());
        var replies = conversationPage.posts().stream()
                .filter(post -> !post.id().equals(postId) && !contextIds.contains(post.id()))
                .toList();
        var relatedPosts = conversationPage.relatedPosts().stream()
                .filter(post -> !post.id().equals(postId) && !contextIds.contains(post.id()))
                .toList();
        return new PostDetail(focal, replies, conversationPage.nextCursor(), contextPosts, relatedPosts);
    }

    private List<TimelinePage.Post> loadConversationContext(
            String accountId, TimelinePage.Post focal, String language, String replySort) {
        var parentId = focal.replyToPostId();
        if (parentId == null || parentId.isBlank()) {
            return List.of();
        }
        validatePostId(parentId);
        var result = graphQlClient.execute(
                accountId, "conversation", conversationVariables(parentId, null, replySort), language);
        var page = responseParser.parseConversation(result.rawJson());
        var postsById = new LinkedHashMap<String, TimelinePage.Post>();
        page.posts().forEach(post -> postsById.putIfAbsent(post.id(), post));
        var context = new ArrayList<TimelinePage.Post>();
        var visited = new HashSet<String>();
        while (parentId != null && visited.add(parentId)) {
            var parent = postsById.get(parentId);
            if (parent == null) {
                break;
            }
            context.add(parent);
            parentId = parent.replyToPostId();
        }
        java.util.Collections.reverse(context);
        return List.copyOf(context);
    }

    private static Map<String, Object> conversationVariables(
            String focalPostId, String cursor, String replySort) {
        var variables = new LinkedHashMap<String, Object>();
        variables.put("focalTweetId", focalPostId);
        variables.put("isReaderMode", false);
        variables.put("rankingMode", rankingMode(replySort));
        variables.put("includePromotedContent", false);
        variables.put("withCommunity", true);
        variables.put("withQuickPromoteEligibilityTweetFields", false);
        variables.put("withBirdwatchNotes", true);
        variables.put("withVoice", true);
        if (cursor != null && !cursor.isBlank()) {
            variables.put("cursor", cursor);
        }
        return variables;
    }

    static String rankingMode(String replySort) {
        var normalized = replySort == null
                ? "relevance"
                : replySort.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "relevance" -> "Relevance";
            case "recency" -> "Recency";
            case "likes" -> "Likes";
            default -> throw new IllegalArgumentException("返信の並び順が不正です。");
        };
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
            TimelinePage.Post post,
            List<TimelinePage.Post> replies,
            String nextCursor,
            List<TimelinePage.Post> contextPosts,
            List<TimelinePage.Post> relatedPosts) {
        public PostDetail(
                TimelinePage.Post post, List<TimelinePage.Post> replies, String nextCursor) {
            this(post, replies, nextCursor, List.of(), List.of());
        }

        public PostDetail(TimelinePage.Post post, List<TimelinePage.Post> replies,
                String nextCursor, List<TimelinePage.Post> contextPosts) {
            this(post, replies, nextCursor, contextPosts, List.of());
        }

        public PostDetail {
            replies = List.copyOf(replies);
            contextPosts = List.copyOf(contextPosts);
            relatedPosts = List.copyOf(relatedPosts);
        }
    }
}
