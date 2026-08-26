package dev.nytweetdeck.xapi.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AndroidDeviceProfileStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndReloadsVersionedProfileWithoutChangingIdentity() {
        var path = temporaryDirectory.resolve("device.json");
        var store = new AndroidDeviceProfileStore(JsonMapper.builder().build(), path.toString());
        var profile = AndroidDeviceProfile.create(
                "Pixel Test", "16", "Google", "google", "test_product", "2026-08-05", "ja");

        store.save(profile);
        var loaded = store.require();

        assertThat(loaded).isEqualTo(profile);
        assertThat(loaded.clientUuid()).hasSize(36);
        assertThat(loaded.deviceId()).hasSize(32);
        assertThat(store.profilePath()).isEqualTo(path.toAbsolutePath().normalize());
    }

    @Test
    void buildsAndroidUserAgentFromVersionedApiAndDeviceProfile() {
        var profile = new AndroidDeviceProfile(
                1,
                "Pixel Test",
                "16",
                "Google",
                "google",
                "test_product",
                "2026-08-05",
                "ja",
                "12345678-1234-1234-1234-123456789012",
                "12345678901234567890123456789012");
        var apiProfile = new AndroidApiProfile(
                "com.twitter.android",
                "12.19.1-release.0",
                312191000,
                URI.create("https://api.twitter.com"),
                URI.create("https://api.x.com/graphql"),
                Map.of(),
                Map.of(),
                Map.of());

        var identity = profile.toIdentity(apiProfile);

        assertThat(identity.userAgent())
                .isEqualTo("TwitterAndroid/12.19.1-release.0 (312191000-r-0) "
                        + "Pixel Test/16 (Google;Pixel Test;google;test_product;0;;0;)");
        assertThat(identity.language()).isEqualTo("ja");
    }

    @Test
    void rejectsHeaderInjectionAndUnknownSchema() {
        assertThatThrownBy(() -> new AndroidDeviceProfile(
                        1,
                        "Pixel\r\nInjected: yes",
                        "16",
                        "Google",
                        "google",
                        "product",
                        "2026-08-05",
                        "ja",
                        "12345678-1234-1234-1234-123456789012",
                        "12345678901234567890123456789012"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("改行");
        assertThatThrownBy(() -> new AndroidDeviceProfile(
                        2,
                        "Pixel",
                        "16",
                        "Google",
                        "google",
                        "product",
                        "2026-08-05",
                        "ja",
                        "12345678-1234-1234-1234-123456789012",
                        "12345678901234567890123456789012"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未対応");
    }
}
