package dev.nytweetdeck.user;

import dev.nytweetdeck.timeline.TimelinePage;
import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.user.UserProfilePage.RelatedUser;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class UserProfileService {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final TimelineResponseParser timelineParser;
    private final ObjectMapper objectMapper;

    public UserProfileService(
            AuthenticatedGraphQlClient graphQlClient,
            TimelineResponseParser timelineParser,
            ObjectMapper objectMapper) {
        this.graphQlClient = graphQlClient;
        this.timelineParser = timelineParser;
        this.objectMapper = objectMapper;
    }

    public UserProfilePage profile(String accountId, String userId) {
        validateId(userId);
        var profileResult = graphQlClient.execute(
                accountId, "userByRestId", Map.of("userId", userId));
        var mutualResult = graphQlClient.execute(
                accountId,
                "followersYouKnow",
                Map.of(
                        "userId", userId,
                        "count", 20,
                        "includePromotedContent", false,
                        "withGrokTranslatedBio", false));
        try {
            var user = findUser(objectMapper.readTree(profileResult.rawJson()), userId);
            if (user == null) {
                throw new XApiHttpException("Xユーザープロフィールが見つかりません。", 404);
            }
            var mutualFollowers = parseUsers(
                    objectMapper.readTree(mutualResult.rawJson()), userId);
            var legacy = object(user, "legacy");
            var core = object(user, "core");
            var avatar = object(user, "avatar");
            var bio = object(user, "profile_bio");
            var location = object(user, "location");
            var website = object(user, "website");
            var counts = object(user, "relationship_counts");
            var perspectives = object(user, "relationship_perspectives");
            var verification = object(user, "verification");
            return new UserProfilePage(
                    text(user, "rest_id"),
                    firstNonNull(text(core, "screen_name"), text(legacy, "screen_name")),
                    firstNonNull(text(core, "name"), text(legacy, "name")),
                    firstNonNull(text(bio, "description"), text(legacy, "description")),
                    firstNonNull(text(avatar, "image_url"), text(legacy, "profile_image_url_https")),
                    firstNonNull(text(user, "profile_banner_url"), text(legacy, "profile_banner_url")),
                    firstNonNull(text(core, "created_at"), text(legacy, "created_at")),
                    firstNonNull(text(location, "location"), text(legacy, "location")),
                    firstNonNull(text(website, "url"), expandedWebsite(legacy)),
                    firstPositive(
                            firstPositive(number(counts, "friends_count"), number(counts, "following")),
                            number(legacy, "friends_count")),
                    firstPositive(
                            firstPositive(number(counts, "followers_count"), number(counts, "followers")),
                            number(legacy, "followers_count")),
                    mutualFollowers.size(),
                    mutualFollowers,
                    bool(verification, "verified")
                            || bool(legacy, "verified")
                            || bool(user, "is_blue_verified"),
                    bool(perspectives, "following") || bool(legacy, "following"),
                    bool(perspectives, "followed_by") || bool(legacy, "followed_by"));
        } catch (JacksonException exception) {
            throw new XApiHttpException("Xユーザープロフィールを解析できません。", exception);
        }
    }

    public TimelinePage timeline(
            String accountId, String userId, String tab, String cursor) {
        validateId(userId);
        var variables = new LinkedHashMap<String, Object>();
        variables.put("userId", userId);
        variables.put("count", 20);
        if (cursor != null && !cursor.isBlank()) {
            variables.put("cursor", cursor);
        }
        String purpose;
        switch (tab) {
            case "all" -> {
                purpose = "userPosts";
                variables.put("includePromotedContent", false);
                variables.put("withQuickPromoteEligibilityTweetFields", false);
                variables.put("withVoice", true);
            }
            case "posts" -> {
                purpose = "userOriginals";
                variables.put("includePromotedContent", false);
                variables.put("withQuickPromoteEligibilityTweetFields", false);
                variables.put("withVoice", true);
            }
            case "highlights" -> {
                purpose = "userHighlights";
                variables.put("includePromotedContent", false);
                variables.put("withVoice", true);
            }
            case "replies" -> {
                purpose = "userReplies";
                variables.put("includePromotedContent", false);
                variables.put("withCommunity", true);
                variables.put("withVoice", true);
            }
            case "media" -> {
                purpose = "userMedia";
                variables.put("includePromotedContent", false);
                variables.put("withClientEventToken", false);
                variables.put("withBirdwatchNotes", false);
                variables.put("withVoice", true);
            }
            default -> throw new IllegalArgumentException("未対応のプロフィールタブです。");
        }
        return timelineParser.parse(graphQlClient.execute(accountId, purpose, variables).rawJson());
    }

    private static ArrayList<RelatedUser> parseUsers(JsonNode root, String excludedUserId) {
        var users = new LinkedHashMap<String, RelatedUser>();
        collectUsers(root, excludedUserId, users);
        return new ArrayList<>(users.values());
    }

    private static void collectUsers(
            JsonNode node, String excludedUserId, Map<String, RelatedUser> users) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            var id = text(node, "rest_id");
            if ("User".equals(text(node, "__typename"))
                    && id != null
                    && !id.equals(excludedUserId)) {
                var core = object(node, "core");
                var avatar = object(node, "avatar");
                users.putIfAbsent(
                        id,
                        new RelatedUser(
                                id,
                                text(core, "screen_name"),
                                text(core, "name"),
                                text(avatar, "image_url")));
                return;
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                collectUsers(property.getValue(), excludedUserId, users);
            }
        } else if (node.isArray()) {
            for (var child : node) collectUsers(child, excludedUserId, users);
        }
    }

    private static JsonNode findUser(JsonNode node, String userId) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            if ("User".equals(text(node, "__typename"))
                    && userId.equals(text(node, "rest_id"))) {
                return node;
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                var found = findUser(property.getValue(), userId);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var found = findUser(child, userId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String expandedWebsite(JsonNode legacy) {
        var urls = legacy == null
                ? null
                : legacy.path("entities").path("url").path("urls");
        return urls != null && urls.isArray() && !urls.isEmpty()
                ? text(urls.get(0), "expanded_url")
                : null;
    }

    private static JsonNode object(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private static long number(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null ? 0 : value.asLong(0);
    }

    private static boolean bool(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value != null && value.asBoolean(false);
    }

    private static long firstPositive(long first, long second) {
        return first > 0 ? first : second;
    }

    private static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }

    private static void validateId(String value) {
        if (value == null || !value.matches("[0-9]{1,24}")) {
            throw new IllegalArgumentException("XユーザーID形式が不正です。");
        }
    }
}
