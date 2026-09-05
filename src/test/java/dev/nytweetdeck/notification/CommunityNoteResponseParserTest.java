package dev.nytweetdeck.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CommunityNoteResponseParserTest {

    private final CommunityNoteResponseParser parser =
            new CommunityNoteResponseParser(JsonMapper.builder().build());

    @Test
    void parsesTheCompleteNoteBodyAndValidatedSourceRanges() {
        var note = parser.parse("""
                {"data":{"birdwatch_note_by_rest_id":{"rest_id":"555","data_v1":{"summary":{
                "text":"Context with source","entities":[
                {"fromIndex":13,"toIndex":19,"ref":{"url":"https://example.com/source"}},
                {"from_index":0,"to_index":7,"ref":{"url":"javascript:bad"}},
                {"fromIndex":50,"toIndex":80,"ref":{"url":"https://example.com/outside"}}
                ]}},"tweet_results":{"result":{"rest_id":"987","media_note_category":"none"}}}}}
                """, "555");

        assertThat(note.noteId()).isEqualTo("555");
        assertThat(note.text()).isEqualTo("Context with source");
        assertThat(note.sources()).containsExactly(
                new CommunityNoteDetail.Source(13, 19, "https://example.com/source"));
        assertThat(note.targetPostId()).isEqualTo("987");
    }

    @Test
    void rejectsAResponseForAnotherNote() {
        assertThatThrownBy(() -> parser.parse("""
                {"data":{"birdwatch_note_by_rest_id":{"rest_id":"999",
                "data_v1":{"summary":{"text":"wrong note"}}}}}
                """, "555"))
                .hasMessageContaining("解析できません");
    }

    @Test
    void parsesNativeTranslationAndItsOwnSourceOffsets() {
        var note = parser.parseTranslation(translation("ja"), "555", "ja");
        assertThat(note.available()).isTrue();
        assertThat(note.text()).isEqualTo("説明 出典");
        assertThat(note.sources()).containsExactly(new CommunityNoteDetail.Source(3, 5, "https://example.com/source"));
        assertThatThrownBy(() -> parser.parseTranslation(translation("en"), "555", "ja"))
                .hasMessageContaining("解析できません");
    }

    @Test
    void treatsUnavailableAsUnprovidedWithoutUsingOriginalText() {
        var note = parser.parseTranslation("""
                {"data":{"birdwatch_note_by_rest_id":{"rest_id":"555",
                "grok_translated_community_note_with_availability":{"is_available":false}}}}
                """, "555", "ja");
        assertThat(note.available()).isFalse();
        assertThat(note.text()).isNull();
        assertThat(note.sources()).isEmpty();
    }

    private String translation(String destination) {
        return """
                {"data":{"birdwatch_note_by_rest_id":{"rest_id":"555",
                "grok_translated_community_note_with_availability":{"is_available":true,"data":{
                "destination_language":"%s","source_language":"en","translation":"説明 出典",
                "rich_text_entities":[{"from_index":"3","to_index":"5",
                "ref":{"url":"https://t.co/short","expanded_url":"https://example.com/source"}},
                {"from_index":"0","to_index":"2","ref":{"url":"javascript:bad"}}]}}}}}
                """.formatted(destination);
    }

    @Test
    void parsesLiveChunksAndTheirTranslatedLinkOffsets() {
        var result = parser.parseLiveTranslation("""
                {"result":{"text":"説明 "}}
                {"result":{"text":"出典","rich_text_entities":[{"fromIndex":3,"toIndex":5,"ref":{"url":"https://t.co/short","expandedUrl":"https://example.com/live"}}]}}
                """, "555", "ja");
        assertThat(result.text()).isEqualTo("説明 出典");
        assertThat(result.sources()).containsExactly(new CommunityNoteDetail.Source(3, 5, "https://example.com/live"));
    }

    @Test
    void rejectsFailedTruncatedAndEmptyLiveStreams() {
        for (var body : java.util.List.of("", "{\"result\":{}}", "{\"error\":\"failed\"}", "{\"result\":{\"text\":\"partial\"}}\n{\"error\":\"failed\"}", "{\"result\":")) {
            assertThatThrownBy(() -> parser.parseLiveTranslation(body, "555", "ja"))
                    .hasMessageContaining("解析できません");
        }
    }
}
