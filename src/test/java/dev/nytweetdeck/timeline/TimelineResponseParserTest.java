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
        var first = page.posts().get(0);
        assertThat(first.id()).isEqualTo("100");
        assertThat(first.text()).contains("#NyTweetDeck");
        assertThat(first.createdAt()).isEqualTo("2018-10-10T20:19:24Z");
        assertThat(first.author().username()).isEqualTo("alice");
        assertThat(first.author().verified()).isTrue();
        assertThat(first.replyCount()).isEqualTo(2);
        assertThat(first.repostCount()).isEqualTo(3);
        assertThat(first.quoteCount()).isEqualTo(4);
        assertThat(first.likeCount()).isEqualTo(5);
        assertThat(first.bookmarkCount()).isEqualTo(6);
        assertThat(first.viewCount()).isEqualTo(1234);
        assertThat(first.liked()).isTrue();
        assertThat(first.bookmarked()).isTrue();
        assertThat(first.quotedPostId()).isEqualTo("99");
        assertThat(first.media()).hasSize(2);
        assertThat(first.media().get(1).url()).isEqualTo("https://video.twimg.com/high.mp4");
        assertThat(page.posts()).extracting(TimelinePage.Post::id).doesNotContain("99");
        assertThat(page.posts().get(1).replyToPostId()).isEqualTo("100");
    }
}
