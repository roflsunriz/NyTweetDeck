package dev.nytweetdeck.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class LayoutSettingsEventBusTest {

    @Test
    void publishesRevisionsToEveryAddressSubscriberUntilItCloses() throws Exception {
        var bus = new LayoutSettingsEventBus();
        var first = new ArrayList<LayoutSettingsEventBus.LayoutSettingsEvent>();
        var second = new ArrayList<LayoutSettingsEventBus.LayoutSettingsEvent>();
        var firstSubscription = bus.subscribe(first::add);
        var secondSubscription = bus.subscribe(second::add);

        bus.publish(4);
        firstSubscription.close();
        bus.publish(5);

        assertThat(first).extracting(LayoutSettingsEventBus.LayoutSettingsEvent::revision)
                .containsExactly(4L);
        assertThat(second).extracting(LayoutSettingsEventBus.LayoutSettingsEvent::revision)
                .containsExactly(4L, 5L);
        assertThat(second.get(0).id()).isNotBlank();
        secondSubscription.close();
    }
}
