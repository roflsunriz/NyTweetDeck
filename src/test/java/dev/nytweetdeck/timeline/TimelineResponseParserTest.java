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

    @Test
    void readsIdentityFromCurrentWebUserSchema() {
        var body = """
                {"data":{"tweet":{"result":{"__typename":"Tweet","rest_id":"200",
                "legacy":{"full_text":"web schema","created_at":"2018-10-10T20:19:24Z"},
                "core":{"user_results":{"result":{"__typename":"User","rest_id":"42",
                "core":{"screen_name":"alice","name":"Alice"},
                "avatar":{"image_url":"https://pbs.twimg.com/profile_images/alice.jpg"},
                "verification":{"verified":true}}}}}}}}
                """;

        var post = parser.parse(body).posts().getFirst();

        assertThat(post.author().id()).isEqualTo("42");
        assertThat(post.author().username()).isEqualTo("alice");
        assertThat(post.author().displayName()).isEqualTo("Alice");
        assertThat(post.author().avatarUrl()).endsWith("alice.jpg");
        assertThat(post.author().verified()).isTrue();
    }

    @Test
    void readsIdentityFromBookmarksUserWrapper() {
        var body = """
                {"data":{"bookmark_timeline_v2":{"timeline":{"instructions":[{"entries":[{
                "content":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
                "rest_id":"201","legacy":{"full_text":"saved post",
                "created_at":"2018-10-10T20:19:24Z"},"core":{"user_results":{"result":
                {"result":{"__typename":"User","rest_id":"43","core":{"screen_name":"bob",
                "name":"Bob"},"avatar":{"image_url":"https://pbs.twimg.com/bob.jpg"}}}}}}}}}}]}
                ]}}}}
                """;

        var post = parser.parse(body).posts().getFirst();

        assertThat(post.author().id()).isEqualTo("43");
        assertThat(post.author().username()).isEqualTo("bob");
        assertThat(post.author().displayName()).isEqualTo("Bob");
        assertThat(post.author().avatarUrl()).isEqualTo("https://pbs.twimg.com/bob.jpg");
    }

    @Test
    void readsIdentityFromBookmarksSingularUserResult() {
        var body = """
                {"data":{"bookmark_timeline_v2":{"timeline":{"instructions":[{"entries":[{
                "content":{"itemContent":{"tweet_results":{"result":{"__typename":"Tweet",
                "rest_id":"202","legacy":{"full_text":"another saved post",
                "created_at":"2018-10-10T20:19:24Z"},"core":{"user_result":{"result":
                {"__typename":"User","rest_id":"44","legacy":{"screen_name":"carol",
                "name":"Carol","profile_image_url_https":"https://pbs.twimg.com/carol.jpg"}}}}}}}}}
                ]}]}}}}
                """;

        var post = parser.parse(body).posts().getFirst();

        assertThat(post.author().id()).isEqualTo("44");
        assertThat(post.author().username()).isEqualTo("carol");
        assertThat(post.author().displayName()).isEqualTo("Carol");
        assertThat(post.author().avatarUrl()).isEqualTo("https://pbs.twimg.com/carol.jpg");
    }

    @Test
    void limitsBookmarksFallbackToTheTweetCoreAuthorArea() {
        var body = """
                {"data":{"bookmark_timeline_v2":{"tweet_results":{"result":{"__typename":"Tweet",
                "rest_id":"203","legacy":{"full_text":"saved schema variant",
                "created_at":"2018-10-10T20:19:24Z"},"core":{"account_reference":{"value":
                {"__typename":"User","rest_id":"45","core":{"screen_name":"dave","name":"Dave"},
                "avatar":{"image_url":"https://pbs.twimg.com/dave.jpg"}}}},"quoted_status_result":
                {"result":{"__typename":"Tweet","rest_id":"999","legacy":{"full_text":"quoted"},
                "core":{"user_results":{"result":{"__typename":"User","rest_id":"99","core":
                {"screen_name":"wrong","name":"Wrong"}}}}}}}}}}}
                """;

        var post = parser.parse(body).posts().getFirst();

        assertThat(post.author().id()).isEqualTo("45");
        assertThat(post.author().username()).isEqualTo("dave");
        assertThat(post.author().displayName()).isEqualTo("Dave");
        assertThat(post.author().avatarUrl()).isEqualTo("https://pbs.twimg.com/dave.jpg");
    }
}
