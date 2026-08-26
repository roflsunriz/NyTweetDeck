package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserActionServiceTest {

    private final UserActionService service = new UserActionService(null);

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
}
