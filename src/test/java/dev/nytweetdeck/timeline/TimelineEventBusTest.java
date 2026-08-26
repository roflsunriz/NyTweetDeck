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
}
