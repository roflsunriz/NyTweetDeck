package dev.nytweetdeck.timeline;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TimelineQueryFactory {

    public Query create(String kind, String target, String cursor) {
        return create(kind, target, cursor, "latest");
    }

    public Query create(String kind, String target, String cursor, String sort) {
        var normalizedSort = normalizeSort(sort);
        var variables = new LinkedHashMap<String, Object>();
        variables.put("count", 20);
        if (cursor != null && !cursor.isBlank()) {
            variables.put("cursor", cursor);
        }
        return switch (kind) {
            case "homeForYou" -> {
                variables.put("enableRanking", "top".equals(normalizedSort));
                variables.put("includePromotedContent", false);
                variables.put("requestContext", "launch");
                variables.put("withCommunity", true);
                yield new Query("homeForYou", variables);
            }
            case "homeFollowing" -> {
                variables.put("enableRanking", "top".equals(normalizedSort));
                variables.put("includePromotedContent", false);
                variables.put("requestContext", "launch");
                yield new Query("homeFollowing", variables);
            }
            case "userPosts" -> {
                variables.put("userId", requireTarget(target, kind));
                variables.put("includePromotedContent", false);
                variables.put("withQuickPromoteEligibilityTweetFields", false);
                variables.put("withVoice", true);
                yield new Query("userPosts", variables);
            }
            case "list" -> {
                variables.put("listId", requireTarget(target, kind));
                yield new Query("list", variables);
            }
            case "history" -> new Query("history", variables);
            case "trends" -> new Query("trends", variables);
            case "notifications" -> new Query("notifications", variables);
            case "search" -> {
                variables.put("rawQuery", requireTarget(target, kind));
                variables.put("querySource", "typed_query");
                variables.put("product", "top".equals(normalizedSort) ? "Top" : "Latest");
                variables.put("withGrokTranslatedBio", false);
                variables.put("withQuickPromoteEligibilityTweetFields", false);
                yield new Query("search", variables);
            }
            default -> throw new IllegalArgumentException("未対応のタイムライン種別です: " + kind);
        };
    }

    private static String normalizeSort(String sort) {
        var normalized = sort == null ? "latest" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("latest") && !normalized.equals("top")) {
            throw new IllegalArgumentException("カラムの並び順が不正です。");
        }
        return normalized;
    }

    private static String requireTarget(String target, String kind) {
        if (target == null || target.isBlank() || target.length() > 200) {
            throw new IllegalArgumentException(kind + "には有効な対象が必要です。");
        }
        return target;
    }

    public record Query(String purpose, Map<String, Object> variables) {
        public Query {
            variables = Map.copyOf(variables);
        }
    }
}
