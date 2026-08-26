package dev.nytweetdeck.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationResponseParserTest {

    @Test
    void parsesConfirmedWebTimelineNotificationAndSanitizesImages() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());
        var notifications = parser.parse("""
                {"entries":[{"content":{"notification":{"id":"follow-1","notification_icon":"person","url":{"url":"twitter://user?screen_name=alice","url_type":"DeepLink"},"socialContext":{"generalContext":{"text":"Alice followed you","contextImageUrls":["https://pbs.twimg.com/alice.jpg","javascript:bad"]}}}}}]}
                """);

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.id()).isEqualTo("follow-1");
            assertThat(notification.kind()).isEqualTo("follow");
            assertThat(notification.text()).isEqualTo("Alice followed you");
            assertThat(notification.postId()).isNull();
            assertThat(notification.imageUrls()).containsExactly("https://pbs.twimg.com/alice.jpg");
        });
    }

    @Test
    void classifiesLikeAndLinksItsTargetPostInsideNyTweetDeck() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());

        var notification = parser.parse("""
                {"notification":{"id":"like-1","notification_icon":"heart",
                "message":{"text":"Alice liked your post"},
                "url":{"url":"https://x.com/alice/status/123"},
                "template":{"target":{"__typename":"Tweet","rest_id":"123"},
                "actor":{"profile_image_url_https":"https://pbs.twimg.com/alice.jpg"}}}}
                """).getFirst();

        assertThat(notification.kind()).isEqualTo("like");
        assertThat(notification.postId()).isEqualTo("123");
        assertThat(notification.imageUrls()).containsExactly("https://pbs.twimg.com/alice.jpg");
    }
}
