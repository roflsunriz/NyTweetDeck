package dev.nytweetdeck.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class TimelineEventBusTest {

    @Test
    void publishesOnlyToMatchingAccountAndStopsAfterClose() throws Exception {
        var bus = new TimelineEventBus();
        var events = new ArrayList<TimelineEventBus.TimelineEvent>();
        var subscription = bus.subscribe("account-a", events::add);

        bus.publish("account-b", "like", "1");
        bus.publish("account-a", "create", "2");

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo("create");
            assertThat(event.postId()).isEqualTo("2");
            assertThat(event.id()).isNotBlank();
        });

        subscription.close();
        bus.publish("account-a", "repost", "3");
        assertThat(events).hasSize(1);
    }

    @Test
    void publishesEngagementCountsWithoutRequestingATimelineReplacement() throws Exception {
        var bus = new TimelineEventBus();
        var events = new ArrayList<TimelineEventBus.TimelineEvent>();
        var subscription = bus.subscribe("account-a", events::add);

        bus.publishEngagement(
                "account-a",
                "10",
                new TimelineEventBus.EngagementCounts(1L, 2L, 3L, 4L, 5L, 6L));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo("live:tweet_engagement");
            assertThat(event.postId()).isEqualTo("10");
            assertThat(event.replyCount()).isEqualTo(1);
            assertThat(event.repostCount()).isEqualTo(2);
            assertThat(event.quoteCount()).isEqualTo(3);
            assertThat(event.likeCount()).isEqualTo(4);
            assertThat(event.bookmarkCount()).isEqualTo(5);
            assertThat(event.viewCount()).isEqualTo(6);
        });
        subscription.close();
    }
}
