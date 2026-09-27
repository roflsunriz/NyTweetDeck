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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PostService {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final TimelineResponseParser responseParser;
    private final TimelineEventBus eventBus;
    private final ComposerWebClient composerWebClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public PostService(
            AuthenticatedGraphQlClient graphQlClient,
            TimelineResponseParser responseParser,
            TimelineEventBus eventBus,
            ComposerWebClient composerWebClient,
            ObjectMapper objectMapper) {
        this.graphQlClient = graphQlClient;
        this.responseParser = responseParser;
        this.eventBus = eventBus;
        this.composerWebClient = composerWebClient;
        this.objectMapper = objectMapper;
    }

    public PostService(
            AuthenticatedGraphQlClient graphQlClient,
            TimelineResponseParser responseParser,
            TimelineEventBus eventBus) {
        this(graphQlClient, responseParser, eventBus, null, JsonMapper.builder().build());
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

    public Object createAdvanced(String accountId, PostComposition composition) {
        validateComposition(composition);
        var cardUri = composition.poll() == null ? null : composerWebClient.createPoll(accountId, composition.poll());
        if (composition.scheduledAt() != null) {
            var variables = scheduledVariables(composition);
            var result = graphQlClient.execute(accountId, "schedulePost", variables);
            try {
                var root = objectMapper.readTree(result.rawJson());
                var idNode = root.path("data").path("tweet").get("rest_id");
                var scheduledId = idNode == null ? null : idNode.asString();
                if (scheduledId == null || !scheduledId.matches("[0-9]{1,30}")) {
                    throw new IllegalStateException("予約投稿の確認IDがありません。");
                }
                return new ScheduledPost(scheduledId, composition.scheduledAt());
            } catch (JacksonException exception) {
                throw new IllegalStateException("予約投稿の応答を解析できません。", exception);
            }
        }
        var result = graphQlClient.execute(accountId, "createPost", advancedVariables(composition, cardUri));
        var post = responseParser.parse(result.rawJson()).posts().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("投稿応答に作成済みポストがありません。"));
        var reason = composition.inReplyToPostId() != null ? "reply"
                : composition.quotePostId() != null ? "quote" : "create";
        eventBus.publish(accountId, reason, post.id());
        return post;
    }

    Map<String, Object> advancedVariables(PostComposition composition, String cardUri) {
        validateComposition(composition);
        var variables = new LinkedHashMap<String, Object>();
        variables.put("tweet_text", composition.text() == null ? "" : composition.text().trim());
        variables.put("nullcast", false);
        variables.put("includeCommunityTweetRelationship", false);
        variables.put("includeTweetVisibilityNudge", true);
        if (composition.inReplyToPostId() != null) variables.put("reply", Map.of(
                "in_reply_to_tweet_id", composition.inReplyToPostId(), "exclude_reply_user_ids", List.of()));
        if (composition.quotePostId() != null) variables.put("attachment_url",
                "https://twitter.com/i/status/" + composition.quotePostId());
        if (!composition.mediaIds().isEmpty()) variables.put("media", Map.of(
                "media_entities", composition.mediaIds().stream().map(id -> Map.of(
                        "media_id", id, "tagged_users", List.of())).toList(),
                "possibly_sensitive", false));
        if (cardUri != null) variables.put("card_uri", cardUri);
        if (composition.place() != null) variables.put("geo", placeVariables(composition.place()));
        var disclosures = disclosureVariables(composition);
        if (!disclosures.isEmpty()) variables.put("content_disclosure", disclosures);
        return Map.copyOf(variables);
    }

    Map<String, Object> scheduledVariables(PostComposition composition) {
        validateComposition(composition);
        var request = new LinkedHashMap<String, Object>();
        request.put("status", composition.text() == null ? "" : composition.text().trim());
        request.put("media_ids", composition.mediaIds());
        request.put("exclude_reply_user_ids", List.of());
        request.put("thread_tweets", List.of());
        var disclosures = disclosureVariables(composition);
        if (!disclosures.isEmpty()) request.put("content_disclosure_options", disclosures);
        return Map.of("post_tweet_request", request, "execute_at", composition.scheduledAt().getEpochSecond());
    }

    static void validateComposition(PostComposition composition) {
        if (composition == null) throw new IllegalArgumentException("投稿内容がありません。");
        var text = composition.text() == null ? "" : composition.text().trim();
        if (text.length() > 4000 || (text.isEmpty() && composition.mediaIds().isEmpty() && composition.poll() == null)) {
            throw new IllegalArgumentException("本文、画像、GIF、投票のいずれかを指定してください。");
        }
        if (composition.mediaIds().size() > 4 || composition.mediaIds().stream().anyMatch(
                id -> id == null || !id.matches("[0-9]{1,30}"))) {
            throw new IllegalArgumentException("画像IDは最大4件の数値で指定してください。");
        }
        if (composition.inReplyToPostId() != null) validatePostId(composition.inReplyToPostId());
        if (composition.quotePostId() != null) validatePostId(composition.quotePostId());
        if (composition.inReplyToPostId() != null && composition.quotePostId() != null) {
            throw new IllegalArgumentException("返信と引用は同時に指定できません。");
        }
        if (composition.poll() != null && (!composition.mediaIds().isEmpty() ||
                composition.quotePostId() != null || composition.inReplyToPostId() != null)) {
            throw new IllegalArgumentException("投票は画像・GIF・返信・引用と同時に指定できません。");
        }
        if (composition.place() != null && (composition.place().id() == null ||
                !composition.place().id().matches("[A-Za-z0-9_-]{1,100}"))) {
            throw new IllegalArgumentException("場所IDの形式が不正です。");
        }
        if (composition.place() != null && composition.place().geoSearchRequestId() != null &&
                !composition.place().geoSearchRequestId().matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("場所検索IDの形式が不正です。");
        }
        if (composition.scheduledAt() != null) {
            if (!composition.scheduledAt().isAfter(java.time.Instant.now().plusSeconds(120)) ||
                    composition.scheduledAt().isAfter(java.time.Instant.now().plusSeconds(365L * 24 * 3600))) {
                throw new IllegalArgumentException("予約日時は2分後から1年以内に指定してください。");
            }
            if (composition.poll() != null || composition.place() != null ||
                    composition.inReplyToPostId() != null || composition.quotePostId() != null) {
                throw new IllegalArgumentException("予約投稿は投票・場所・返信・引用と組み合わせられません。");
            }
        }
    }

    private static Map<String, Object> disclosureVariables(PostComposition composition) {
        var disclosures = new LinkedHashMap<String, Object>();
        if (composition.paidPartnership()) disclosures.put("advertising_disclosure", Map.of("is_paid_promotion", true));
        if (composition.aiGenerated()) disclosures.put("ai_generated_disclosure", Map.of(
                "has_ai_generated_media", true, "ai_generated_detection_source", "UserDeclared"));
        return disclosures;
    }

    private static Map<String, String> placeVariables(PostComposition.Place place) {
        var geo = new LinkedHashMap<String, String>();
        geo.put("place_id", place.id());
        if (place.geoSearchRequestId() != null && !place.geoSearchRequestId().isBlank()) {
            geo.put("geo_search_request_id", place.geoSearchRequestId());
        }
        return geo;
    }

    public record ScheduledPost(String scheduledId, java.time.Instant scheduledAt) {}

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
