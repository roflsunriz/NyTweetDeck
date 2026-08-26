package dev.nytweetdeck.xapi.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.xapi.oauth.OAuth1Signer.Credentials;
import dev.nytweetdeck.xapi.oauth.OAuth1Signer.Parameter;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class OAuth1SignerTest {

    private final OAuth1Signer signer = new OAuth1Signer();

    @Test
    void matchesRfc5849SignatureExample() {
        var credentials = new Credentials(
                "9djdj82h48djs9d2", "j49sk3j29djd", "kkk9d7dh3k39sjv7", "dh893hdasih9");
        var uri = URI.create("http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r%20b");

        var header = signer.authorizationHeader(
                "POST",
                uri,
                List.of(new Parameter("c2", ""), new Parameter("a3", "2 q")),
                credentials,
                "137131201",
                "7d8f3e4a",
                false);

        assertThat(header).contains("oauth_signature=\"r6%2FTJjbCOr97%2F%2BUU0NsvSne7s5g%3D\"");
    }

    @Test
    void normalizesDefaultPortsAndEncodesUnicode() {
        assertThat(OAuth1Signer.normalizeBaseUri(URI.create("HTTPS://Example.COM:443/投稿")))
                .isEqualTo("https://example.com/投稿");
        assertThat(OAuth1Signer.encode("日本語 ~"))
                .isEqualTo("%E6%97%A5%E6%9C%AC%E8%AA%9E%20~");
    }

    @Test
    void includesAndroidOAuthVersionByDefault() {
        var credentials = new Credentials("consumer", "secret", null, null);

        var header = signer.authorizationHeader(
                "GET", URI.create("https://api.twitter.com/1.1/test.json"), List.of(),
                credentials, "1", "nonce");

        assertThat(header).contains("oauth_version=\"1.0\"");
    }
}
