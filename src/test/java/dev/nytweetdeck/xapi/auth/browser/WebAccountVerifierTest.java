package dev.nytweetdeck.xapi.auth.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebAccountVerifierTest {

    private final WebAccountVerifier verifier = new WebAccountVerifier();

    @Test
    void createsAccountFromXCookiesAndBrowserIdentityWithoutAnotherApiRequest() {
        var account = verifier.verify(
                Map.of("auth_token", "auth", "ct0", "csrf", "twid", "u%3D42"),
                "bearer",
                new WebAccountVerifier.BrowserIdentity("alice", "Alice"));

        assertThat(account.accountId()).isEqualTo("42");
        assertThat(account.username()).isEqualTo("alice");
        assertThat(account.displayName()).isEqualTo("Alice");
        assertThat(account.authToken()).isEqualTo("auth");
    }

    @Test
    void fallsBackToUserIdWhenCollapsedNavigationHasNoVisibleIdentity() {
        var account = verifier.verify(
                Map.of("auth_token", "auth", "ct0", "csrf", "twid", "u=900"),
                "bearer",
                new WebAccountVerifier.BrowserIdentity("", ""));

        assertThat(account.username()).isEqualTo("900");
        assertThat(account.displayName()).isEqualTo("900");
    }

    @Test
    void rejectsIncompleteCookieSets() {
        assertThatThrownBy(() -> verifier.verify(
                        Map.of("auth_token", "auth", "twid", "u=42"),
                        "bearer",
                        new WebAccountVerifier.BrowserIdentity("alice", "Alice")))
                .isInstanceOf(XApiHttpException.class)
                .hasMessageContaining("必要なCookie");
    }
}
