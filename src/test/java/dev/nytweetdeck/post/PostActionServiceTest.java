package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PostActionServiceTest {

    private final PostActionService service = new PostActionService(null);

    @Test
    void mapsAndroidMutationVariablesForEverySupportedAction() {
        assertThat(service.createRequest("123", "like").variables())
                .containsEntry("tweet_id", "123");
        assertThat(service.createRequest("123", "unlike").purpose()).isEqualTo("unlike");
        assertThat(service.createRequest("123", "repost").purpose()).isEqualTo("repost");
        assertThat(service.createRequest("123", "undoRepost").variables())
                .containsEntry("source_tweet_id", "123");
        assertThat(service.createRequest("123", "bookmark").purpose()).isEqualTo("bookmark");
        assertThat(service.createRequest("123", "removeBookmark").purpose())
                .isEqualTo("removeBookmark");
    }

    @Test
    void rejectsUnknownActionAndInvalidPostId() {
        assertThatThrownBy(() -> service.createRequest("123", "block"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未対応");
        assertThatThrownBy(() -> service.createRequest("not-a-number", "like"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
    }
}
