package dev.nytweetdeck.trend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TrendResponseParserTest {

    @Test
    void parsesConfirmedWebUrtTrendFieldsAndCursor() {
        var parser = new TrendResponseParser(JsonMapper.builder().build());
        var page = parser.parse("""
                {"data":{"timeline":{"entries":[{"content":{"trend":{"name":"#NyTweetDeck","description":"1,234 posts","rank":"1","url":{"url":"twitter://search?query=NyTweetDeck","url_type":"DeepLink"},"trendMetadata":{"domain_context":"Technology","metaDescription":"Trending now"}}}},{"content":{"cursorType":"Bottom","value":"next"}}]}}}
                """);

        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.trends()).singleElement().satisfies(trend -> {
            assertThat(trend.name()).isEqualTo("#NyTweetDeck");
            assertThat(trend.description()).isEqualTo("1,234 posts");
            assertThat(trend.domainContext()).isEqualTo("Technology");
            assertThat(trend.url()).startsWith("https://x.com/search?q=");
        });
    }
}
