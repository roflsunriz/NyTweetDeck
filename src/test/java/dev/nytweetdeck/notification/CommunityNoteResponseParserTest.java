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
}
