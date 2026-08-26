package dev.nytweetdeck.post;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostServiceTest {

    @Test
    void validatesPostIdsAndPostLengthBeforeNetwork() {
        assertThatThrownBy(() -> PostService.validatePostId("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
        assertThatThrownBy(() -> new PostService(null, null, null).create("account", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1〜4000");
        assertThatThrownBy(() -> new PostService(null, null, null).create(
                        "account", "a".repeat(4001), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1〜4000");
        assertThatThrownBy(() -> new PostService(null, null, null)
                        .create("account", "quote", null, "invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式");
    }

    @Test
    void buildsVerifiedAndroidQuoteAttachmentUrl() {
        var variables = new PostService(null, null, null)
                .createVariables(" quote ", null, "123456789");

        assertThat(variables)
                .containsEntry("tweet_text", "quote")
                .containsEntry("attachment_url", "https://twitter.com/i/status/123456789");
    }
}
