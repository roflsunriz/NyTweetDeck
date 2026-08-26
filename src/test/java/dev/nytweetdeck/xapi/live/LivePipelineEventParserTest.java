package dev.nytweetdeck.xapi.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LivePipelineEventParserTest {

    private final LivePipelineEventParser parser =
            new LivePipelineEventParser(JsonMapper.builder().build());

    @Test
    void parsesConfirmedAndroidTweetEngagementEnvelope() {
        var event = parser.parse("""
                {"topic":"/tweet_engagement/123","payload":{"tweet_engagement":{"favorite_count":"8","retweet_count":"3"}}}
                """);

        assertThat(event.type()).isEqualTo("tweet_engagement");
        assertThat(event.entityId()).isEqualTo("123");
        assertThat(event.payload().get("favorite_count").asString()).isEqualTo("8");
    }

    @Test
    void rejectsUnknownOrMalformedEvents() {
        assertThatThrownBy(() -> parser.parse("""
                {"topic":"/future/1","payload":{"future":{"value":"1"}}}
                """))
                .isInstanceOf(XApiHttpException.class)
                .hasMessageContaining("解析");
    }
}
