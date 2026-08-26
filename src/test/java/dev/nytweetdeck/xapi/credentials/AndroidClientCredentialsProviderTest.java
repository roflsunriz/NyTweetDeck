package dev.nytweetdeck.xapi.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AndroidClientCredentialsProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsEmptyWhenCredentialFileDoesNotExist() {
        var provider = new AndroidClientCredentialsProvider(
                temporaryDirectory.resolve("missing.properties").toString());

        assertThat(provider.find()).isEmpty();
        assertThatThrownBy(provider::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未設定");
    }

    @Test
    void loadsCredentialsWithoutExposingThemThroughPathOrToString() throws Exception {
        var path = temporaryDirectory.resolve("credentials.properties");
        Files.writeString(path, "consumerKey=test-key\nconsumerSecret=test-secret\n");
        var provider = new AndroidClientCredentialsProvider(path.toString());

        var credentials = provider.require();

        assertThat(credentials.consumerKey()).isEqualTo("test-key");
        assertThat(credentials.consumerSecret()).isEqualTo("test-secret");
        assertThat(credentials.toString()).doesNotContain("test-key", "test-secret");
        assertThat(provider.credentialsPath()).isEqualTo(path.toAbsolutePath().normalize());
    }
}
