package dev.nytweetdeck.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TimelineResponseParserTest {

    private final TimelineResponseParser parser =
            new TimelineResponseParser(JsonMapper.builder().build());

    @Test
    void normalizesPostsMediaMetricsAndCursorFromGraphQlUrtResponse() throws Exception {
        String body;
        try (var input = getClass().getResourceAsStream("/fixtures/timeline-response.json")) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        var page = parser.parse(body);

        assertThat(page.nextCursor()).isEqualTo("next-cursor");
        assertThat(page.posts()).hasSize(2);
        assertThat(page.posts()).extracting(TimelinePage.Post::id).containsExactly("101", "100");
        var detailed = page.posts().get(1);
        assertThat(detailed.text()).contains("#NyTweetDeck");
        assertThat(detailed.createdAt()).isEqualTo("2018-10-10T20:19:24Z");
        assertThat(detailed.author().username()).isEqualTo("alice");
        assertThat(detailed.author().verified()).isTrue();
        assertThat(detailed.replyCount()).isEqualTo(2);
        assertThat(detailed.repostCount()).isEqualTo(3);
        assertThat(detailed.quoteCount()).isEqualTo(4);
        assertThat(detailed.likeCount()).isEqualTo(5);
        assertThat(detailed.bookmarkCount()).isEqualTo(6);
        assertThat(detailed.viewCount()).isEqualTo(1234);
        assertThat(detailed.liked()).isTrue();
        assertThat(detailed.bookmarked()).isTrue();
        assertThat(detailed.quotedPostId()).isEqualTo("99");
        assertThat(detailed.media()).hasSize(2);
        assertThat(detailed.media().get(1).url()).isEqualTo("https://video.twimg.com/high.mp4");
        assertThat(page.posts()).extracting(TimelinePage.Post::id).doesNotContain("99");
        assertThat(page.posts().getFirst().replyToPostId()).isEqualTo("100");
    }
}
