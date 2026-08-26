package dev.nytweetdeck.xapi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient.Parameter;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthenticatedRestClientTest {

    @Test
    void encodesFormBodyUsingOauthCompatiblePercentEncoding() {
        assertThat(AuthenticatedRestClient.formBody(List.of(
                        new Parameter("user_id", "42"),
                        new Parameter("note", "a b+c"))))
                .isEqualTo("user_id=42&note=a%20b%2Bc");
    }
}
