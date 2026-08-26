package dev.nytweetdeck.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TimelineQueryFactoryTest {

    private final TimelineQueryFactory factory = new TimelineQueryFactory();

    @Test
    void buildsPaginatedQueriesForEverySupportedColumn() {
        assertThat(factory.create("homeForYou", null, "cursor").variables())
                .containsEntry("cursor", "cursor")
                .containsEntry("count", 20)
                .containsEntry("includePromotedContent", true);
        assertThat(factory.create("homeFollowing", null, null).purpose())
                .isEqualTo("homeFollowing");
        assertThat(factory.create("userPosts", "42", null).variables())
                .containsEntry("rest_id", "42");
        assertThat(factory.create("list", "84", null).variables())
                .containsEntry("rest_id", "84");
        assertThat(factory.create("history", null, null).purpose()).isEqualTo("history");
        assertThat(factory.create("search", "NyTweetDeck", null).variables())
                .containsEntry("rawQuery", "NyTweetDeck")
                .containsEntry("product", "Latest");
    }

    @Test
    void rejectsUnknownKindAndMissingTarget() {
        assertThatThrownBy(() -> factory.create("unknown", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未対応");
        assertThatThrownBy(() -> factory.create("userPosts", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("対象");
    }
}
