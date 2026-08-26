package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ListMembershipServiceTest {

    private final ListMembershipService service = new ListMembershipService(null);

    @Test
    void mapsConfirmedWebListMembershipVariables() {
        var add = service.createRequest("42", "84", "add");
        var remove = service.createRequest("42", "84", "remove");

        assertThat(add.purpose()).isEqualTo("listMemberAdd");
        assertThat(remove.purpose()).isEqualTo("listMemberRemove");
        assertThat(add.variables())
                .containsEntry("user_id", "42")
                .containsEntry("list_id", "84");
    }

    @Test
    void rejectsInvalidIdsAndActions() {
        assertThatThrownBy(() -> service.createRequest("alice", "84", "add"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createRequest("42", "list", "add"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createRequest("42", "84", "toggle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未対応");
    }
}
