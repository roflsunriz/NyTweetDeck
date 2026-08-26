package dev.nytweetdeck.xapi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient.Parameter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthenticatedRestClientTest {

    @Test
    void encodesFormBodyUsingOauthCompatiblePercentEncoding() {
        assertThat(AuthenticatedRestClient.formBody(List.of(
                        new Parameter("user_id", "42"),
                        new Parameter("note", "a b+c"))))
                .isEqualTo("user_id=42&note=a%20b%2Bc");
    }

    @Test
    void expandsOnlyDeclaredRestPathVariables() {
        assertThat(AuthenticatedRestClient.expandPath(
                        "/1.1/strato/tweetId={postId}/translate", Map.of("postId", "123")))
                .isEqualTo("/1.1/strato/tweetId=123/translate");
    }
}
