package dev.nytweetdeck.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DirectMessageResponseParserTest {

    @Test
    void parsesWebInboxEntriesAndUsersNewestFirst() {
        var parser = new DirectMessageResponseParser(JsonMapper.builder().build());
        var page = parser.parse("""
                {"inbox_initial_state":{"cursor":"99","users":{"42":{"name":"Alice","screen_name":"alice","profile_image_url_https":"https://img.example/a.jpg"}},"entries":[{"message":{"id":"1","time":"100","conversation_id":"42-7","message_data":{"sender_id":"42","text":"hello"}}},{"message":{"id":"2","time":"200","conversation_id":"42-7","message_data":{"sender_id":"42","text":"newest"}}}]}}
                """);

        assertThat(page.nextCursor()).isEqualTo("99");
        assertThat(page.messages()).extracting(DirectMessagePage.DirectMessage::id)
                .containsExactly("2", "1");
        assertThat(page.messages().getFirst().senderUsername()).isEqualTo("alice");
    }
}
