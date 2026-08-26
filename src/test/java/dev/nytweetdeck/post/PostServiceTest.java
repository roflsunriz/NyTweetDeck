package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PostServiceTest {

    @Test
    void validatesPostIdsAndPostLengthBeforeNetwork() {
        assertThatThrownBy(() -> PostService.validatePostId("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
        assertThatThrownBy(() -> new PostService(null, null, null).create("account", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1〜4000");
        assertThatThrownBy(() -> new PostService(null, null, null).create(
                        "account", "a".repeat(4001), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1〜4000");
    }
}
