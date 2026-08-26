package dev.nytweetdeck.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationResponseParserTest {

    @Test
    void parsesConfirmedAndroidTimelineNotificationAndSanitizesUrls() {
        var parser = new NotificationResponseParser(JsonMapper.builder().build());
        var notifications = parser.parse("""
                {"entries":[{"content":{"notification":{"id":"follow-1","url":{"url":"twitter://user?screen_name=alice","url_type":"DeepLink"},"socialContext":{"generalContext":{"text":"Alice followed you","contextImageUrls":["https://pbs.twimg.com/alice.jpg","javascript:bad"]}}}}}]}
                """);

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.id()).isEqualTo("follow-1");
            assertThat(notification.text()).isEqualTo("Alice followed you");
            assertThat(notification.url()).isEqualTo("https://x.com/notifications");
            assertThat(notification.imageUrls()).containsExactly("https://pbs.twimg.com/alice.jpg");
        });
    }
}
