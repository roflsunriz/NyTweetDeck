package dev.nytweetdeck.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDataPathsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesStableWindowsLocalAppDataRegardlessOfInstallDirectory() {
        var localAppData = temporaryDirectory.resolve("AppData/Local");

        var first = ApplicationDataPaths.resolve(
                "",
                "accounts.json",
                Map.of("LOCALAPPDATA", localAppData.toString()),
                "Windows 11",
                temporaryDirectory.resolve("home"),
                temporaryDirectory.resolve("first-install"));
        var moved = ApplicationDataPaths.resolve(
                "",
                "accounts.json",
                Map.of("LOCALAPPDATA", localAppData.toString()),
                "Windows 11",
                temporaryDirectory.resolve("home"),
                temporaryDirectory.resolve("second-install"));

        assertThat(first).isEqualTo(localAppData.resolve("NyTweetDeck/accounts.json"));
        assertThat(moved).isEqualTo(first);
    }

    @Test
    void usesStableMacAndLinuxUserDataDirectories() {
        var home = temporaryDirectory.resolve("home");
        var working = temporaryDirectory.resolve("install");

        assertThat(ApplicationDataPaths.resolve(
                        "", "accounts.json", Map.of(), "Mac OS X", home, working))
                .isEqualTo(home.resolve("Library/Application Support/NyTweetDeck/accounts.json"));
        assertThat(ApplicationDataPaths.resolve(
                        "", "accounts.json", Map.of(), "Linux", home, working))
                .isEqualTo(home.resolve(".local/share/NyTweetDeck/accounts.json"));
        assertThat(ApplicationDataPaths.resolve(
                        "",
                        "accounts.json",
                        Map.of("XDG_DATA_HOME", temporaryDirectory.resolve("xdg").toString()),
                        "Linux",
                        home,
                        working))
                .isEqualTo(temporaryDirectory.resolve("xdg/NyTweetDeck/accounts.json"));
    }

    @Test
    void honorsAnExplicitRelativePathFromTheWorkingDirectory() {
        var working = temporaryDirectory.resolve("project");

        var resolved = ApplicationDataPaths.resolve(
                "custom/accounts.json",
                "ignored.json",
                Map.of(),
                "Windows 11",
                temporaryDirectory.resolve("home"),
                working);

        assertThat(resolved).isEqualTo(working.resolve("custom/accounts.json"));
    }
}
