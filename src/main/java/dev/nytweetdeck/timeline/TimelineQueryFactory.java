package dev.nytweetdeck.timeline;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TimelineQueryFactory {

    public Query create(String kind, String target, String cursor) {
        var variables = new LinkedHashMap<String, Object>();
        variables.put("count", 20);
        if (cursor != null && !cursor.isBlank()) {
            variables.put("cursor", cursor);
        }
        return switch (kind) {
            case "homeForYou" -> {
                variables.put("includePromotedContent", true);
                variables.put("latestControlAvailable", true);
                variables.put("requestContext", "launch");
                yield new Query("homeForYou", variables);
            }
            case "homeFollowing" -> {
                variables.put("includePromotedContent", true);
                yield new Query("homeFollowing", variables);
            }
            case "userPosts" -> {
                variables.put("rest_id", requireTarget(target, kind));
                yield new Query("userPosts", variables);
            }
            case "list" -> {
                variables.put("rest_id", requireTarget(target, kind));
                yield new Query("list", variables);
            }
            case "history" -> new Query("history", variables);
            case "trends" -> new Query("trends", variables);
            case "notifications" -> new Query("notifications", variables);
            case "search" -> {
                variables.put("rawQuery", requireTarget(target, kind));
                variables.put("querySource", "typed_query");
                variables.put("product", "Latest");
                yield new Query("search", variables);
            }
            default -> throw new IllegalArgumentException("未対応のタイムライン種別です: " + kind);
        };
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
