package dev.nytweetdeck.xapi.live;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.account.AccountSecrets;
import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.xapi.profile.XApiProfileService;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LivePipelineClientTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsTheCurrentWebEventRequestWithOneEventStreamAcceptHeader() {
        var mapper = JsonMapper.builder().build();
        var accountStore = accountStore(mapper);
        var client = new LivePipelineClient(
                HttpClient.newHttpClient(),
                new XApiProfileService(mapper),
                accountStore,
                mapper);

        var request = client.createRequest(
                "account-1", Set.of("/tweet_engagement/2", "/tweet_engagement/1"));

        assertThat(request.uri().toString())
                .isEqualTo("https://api.x.com/live_pipeline/events?topic="
                        + "%2Ftweet_engagement%2F1%2C%2Ftweet_engagement%2F2");
        assertThat(request.headers().allValues("Accept"))
                .containsExactly("text/event-stream");
        assertThat(request.headers().firstValue("Cookie"))
                .contains("auth_token=auth-token; ct0=csrf-token");
    }

    @Test
    void parsesTheOfficialSessionConfigurationAndDefaultsAnInvalidTtl() {
        var mapper = JsonMapper.builder().build();
        var client = new LivePipelineClient(
                HttpClient.newHttpClient(),
                new XApiProfileService(mapper),
                accountStore(mapper),
                mapper);

        var config = client.parseSessionConfig("""
                {"topic":"/system/config","payload":{"config":{"session_id":"session-1","subscription_ttl_millis":0}}}
                """);

        assertThat(config.sessionId()).isEqualTo("session-1");
        assertThat(config.subscriptionTtlMilliseconds()).isEqualTo(120_000L);
        assertThat(client.parseSessionConfig("""
                {"topic":"/tweet_engagement/1","payload":{"tweet_engagement":{}}}
                """)).isNull();
    }

    private AccountStore accountStore(JsonMapper mapper) {
        var store = new AccountStore(mapper, temporaryDirectory.resolve("accounts.json"));
        store.addOrReplace(AccountSecrets.webSession(
                "account-1",
                "1",
                "alice",
                "Alice",
                "web-bearer",
                "auth-token",
                "csrf-token"));
        return store;
    }
}
