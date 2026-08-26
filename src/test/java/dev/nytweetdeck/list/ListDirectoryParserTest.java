package dev.nytweetdeck.list;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ListDirectoryParserTest {

    @Test
    void parsesCurrentWebListsWithOwnerAndCursor() {
        var parser = new ListDirectoryParser(JsonMapper.builder().build());

        var page = parser.parse("""
                {"data":{"user":{"result":{"timeline":{"timeline":{"instructions":[{"entries":[
                {"content":{"itemContent":{"__typename":"TimelineTwitterList","itemType":"TimelineTwitterList",
                "list":{"id":"84","id_str":"84","name":"Friends","description":"People I know",
                "member_count":5,"subscriber_count":2,"user_results":{"result":{"__typename":"User",
                "core":{"name":"Alice","screen_name":"alice"}}}}}}},
                {"entryId":"cursor-bottom","content":{"cursorType":"Bottom","value":"next"}}
                ]}]}}}}}}
                """, "mine");

        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.lists()).singleElement().satisfies(list -> {
            assertThat(list.id()).isEqualTo("84");
            assertThat(list.name()).isEqualTo("Friends");
            assertThat(list.ownerUsername()).isEqualTo("alice");
            assertThat(list.source()).isEqualTo("mine");
        });
    }
}
