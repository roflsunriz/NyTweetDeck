package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.timeline.TimelineEventBus;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserActionServiceTest {

    private final UserActionService service = new UserActionService(null, new TimelineEventBus());

    @Test
    void mapsConfirmedWebUserActionEndpoints() {
        assertThat(service.createRequest("42", "follow").endpoint()).isEqualTo("followUser");
        assertThat(service.createRequest("42", "mute").endpoint()).isEqualTo("muteUser");
        assertThat(service.createRequest("42", "block").endpoint()).isEqualTo("blockUser");
        assertThat(service.createRequest("42", "follow").parameters())
                .containsEntry("user_id", "42");
    }

    @Test
    void rejectsUnknownActionsAndInvalidIds() {
        assertThatThrownBy(() -> service.createRequest("42", "report"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未対応");
        assertThatThrownBy(() -> service.createRequest("alice", "follow"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
    }

    @Test
    void publishesMuteAndBlockOnlyAfterTheRestActionSucceeds() throws Exception {
        var restClient = new AuthenticatedRestClient(null, null, null) {
            @Override
            public RestResult postForm(
                    String accountId, String endpointName, Map<String, String> parameters) {
                return null;
            }
        };
        var eventBus = new TimelineEventBus();
        var events = new ArrayList<TimelineEventBus.TimelineEvent>();
        var subscription = eventBus.subscribe("account-a", events::add);
        var actionService = new UserActionService(restClient, eventBus);

        actionService.execute("account-a", "42", "follow");
        actionService.execute("account-a", "42", "mute");
        actionService.execute("account-a", "43", "block");

        assertThat(events).extracting(TimelineEventBus.TimelineEvent::reason)
                .containsExactly("mute", "block");
        assertThat(events).extracting(TimelineEventBus.TimelineEvent::userId)
                .containsExactly("42", "43");
        subscription.close();
    }
}
