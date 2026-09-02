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
                .containsEntry("includePromotedContent", false)
                .containsEntry("withCommunity", true)
                .doesNotContainKey("latestControlAvailable");
        assertThat(factory.create("homeFollowing", null, null).variables())
                .containsEntry("enableRanking", false)
                .containsEntry("requestContext", "launch");
        assertThat(factory.create("userPosts", "42", null).variables())
                .containsEntry("userId", "42")
                .containsEntry("withQuickPromoteEligibilityTweetFields", false);
        assertThat(factory.create("list", "84", null).variables())
                .containsEntry("listId", "84");
        assertThat(factory.create("history", null, null).purpose()).isEqualTo("history");
        assertThat(factory.create("trends", null, null).purpose()).isEqualTo("trends");
        assertThat(factory.create("notifications", null, null).purpose())
                .isEqualTo("notifications");
        assertThat(factory.create("search", "NyTweetDeck", null).variables())
                .containsEntry("rawQuery", "NyTweetDeck")
                .containsEntry("product", "Latest")
                .containsEntry("withGrokTranslatedBio", false)
                .containsEntry("withQuickPromoteEligibilityTweetFields", false);
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

    @Test
    void mapsTopAndLatestToTheNativeSearchProducts() {
        assertThat(factory.create("search", "NyTweetDeck", null, "top").variables())
                .containsEntry("product", "Top");
        assertThat(factory.create("search", "NyTweetDeck", null, "latest").variables())
                .containsEntry("product", "Latest");
        assertThatThrownBy(() -> factory.create("search", "NyTweetDeck", null, "popular"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("並び順");
    }
}
