package dev.nytweetdeck.trend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TrendResponseParserTest {

    @Test
    void parsesCurrentExploreTimelineTrendAndCursor() {
        var parser = new TrendResponseParser(JsonMapper.builder().build());
        var page = parser.parse("""
                {
                  "data": {
                    "explore_page": {
                      "body": {
                        "initialTimeline": {
                          "timeline": {
                            "timeline": {
                              "instructions": [{
                                "entries": [
                                  {
                                    "content": {
                                      "entryType": "TimelineTimelineItem",
                                      "itemContent": {
                                        "__typename": "TimelineTrend",
                                        "itemType": "TimelineTrend",
                                        "name": "#NyTweetDeck",
                                        "trend_metadata": {
                                          "domain_context": "Technology",
                                          "url": ""
                                        },
                                        "trend_url": {
                                          "url": "twitter://search?query=NyTweetDeck",
                                          "urlType": "DeepLink"
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "content": {
                                      "entryType": "TimelineTimelineCursor",
                                      "cursorType": "Bottom",
                                      "value": "next-current"
                                    }
                                  }
                                ]
                              }]
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """);

        assertThat(page.nextCursor()).isEqualTo("next-current");
        assertThat(page.trends()).singleElement().satisfies(trend -> {
            assertThat(trend.name()).isEqualTo("#NyTweetDeck");
            assertThat(trend.domainContext()).isEqualTo("Technology");
            assertThat(trend.url()).isEqualTo("https://x.com/search?q=%23NyTweetDeck");
        });
    }
}
