package dev.nytweetdeck.notification;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.net.URI;
import java.util.ArrayList;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CommunityNoteResponseParser {

    private final ObjectMapper objectMapper;

    public CommunityNoteResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedNote parse(String body, String expectedNoteId) {
        try {
            var root = objectMapper.readTree(body);
            var note = root.path("data").path("birdwatch_note_by_rest_id");
            if (!note.isObject()) {
                throw new IllegalArgumentException("コミュニティノート応答に対象ノートがありません。");
            }
            var noteId = note.path("rest_id").asString("");
            if (!expectedNoteId.equals(noteId)) {
                throw new IllegalArgumentException("コミュニティノートIDが応答と一致しません。");
            }
            var summary = note.path("data_v1").path("summary");
            var text = summary.path("text").asString("");
            if (text.isBlank()) {
                throw new IllegalArgumentException("コミュニティノート本文がありません。");
            }
            var sources = new ArrayList<CommunityNoteDetail.Source>();
            var entities = summary.path("entities");
            if (entities.isArray()) {
                for (var entity : entities) {
                    var url = entity.path("ref").path("url").asString("");
                    if (!isSafeWebUrl(url)) continue;
                    var fromIndex = integer(entity, "fromIndex", "from_index");
                    var toIndex = integer(entity, "toIndex", "to_index");
                    if (fromIndex < 0 || toIndex <= fromIndex || toIndex > text.length()) continue;
                    sources.add(new CommunityNoteDetail.Source(fromIndex, toIndex, url));
                }
            }
            var targetPostId = note.path("tweet_results").path("result").path("rest_id").asString("");
            if (!targetPostId.matches("[0-9]{1,24}")) {
                throw new IllegalArgumentException("コミュニティノートの対象ポストIDがありません。");
            }
            return new ParsedNote(noteId, text, sources, targetPostId);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("コミュニティノート応答を解析できません。", exception);
        }
    }

    private static int integer(JsonNode node, String camelCase, String snakeCase) {
        var value = node.get(camelCase);
        if (value == null) value = node.get(snakeCase);
        return value == null ? -1 : value.asInt(-1);
    }

    private static boolean isSafeWebUrl(String value) {
        try {
            var uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record ParsedNote(
            String noteId,
            String text,
            java.util.List<CommunityNoteDetail.Source> sources,
            String targetPostId) {
        public ParsedNote {
            sources = java.util.List.copyOf(sources);
        }
    }
}
