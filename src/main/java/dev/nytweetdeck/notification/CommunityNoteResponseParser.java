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
            return new ParsedNote(noteId, text, sources, targetPostId,
                    note.path("language").asString(null),
                    note.path("is_community_note_translatable").isBoolean()
                            ? note.path("is_community_note_translatable").asBoolean() : null);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("コミュニティノート応答を解析できません。", exception);
        }
    }

    private static int integer(JsonNode node, String camelCase, String snakeCase) {
        var value = node.get(camelCase);
        if (value == null) value = node.get(snakeCase);
        return value == null ? -1 : value.asInt(-1);
    }

    public CommunityNoteTranslation parseTranslation(String body, String noteId, String target) {
        try {
            var note = objectMapper.readTree(body).path("data").path("birdwatch_note_by_rest_id");
            if (!noteId.equals(note.path("rest_id").asString(""))) {
                throw new IllegalArgumentException("コミュニティノートIDが応答と一致しません。");
            }
            var translated = note.path("grok_translated_community_note_with_availability");
            var data = translated.path("data");
            if (!translated.path("is_available").asBoolean(false) || !data.isObject()) {
                return new CommunityNoteTranslation(noteId, false, null, null, target, "X", java.util.List.of());
            }
            var text = data.path("translation").asString("");
            if (text.isBlank() || !target.equalsIgnoreCase(data.path("destination_language").asString(""))) {
                throw new IllegalArgumentException("コミュニティノート翻訳の本文または言語が不正です。");
            }
            var sources = new ArrayList<CommunityNoteDetail.Source>();
            for (var entity : data.path("rich_text_entities")) {
                var ref = entity.path("ref");
                var url = ref.path("expanded_url").asString(ref.path("url").asString(""));
                var from = integer(entity, "fromIndex", "from_index");
                var to = integer(entity, "toIndex", "to_index");
                if (isSafeWebUrl(url) && from >= 0 && to > from && to <= text.length()) {
                    sources.add(new CommunityNoteDetail.Source(from, to, url));
                }
            }
            return new CommunityNoteTranslation(noteId, true, text,
                    data.path("source_language").asString(null), target, "X", sources);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("コミュニティノート翻訳を解析できません。", exception);
        }
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
            String targetPostId,
            String language,
            Boolean isTranslatable) {
        public ParsedNote {
            sources = java.util.List.copyOf(sources);
        }
    }
}
