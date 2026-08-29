package dev.nytweetdeck.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LayoutSettingsStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsOneRevisionedLayoutForEveryAddressAndRestart() {
        var path = temporaryDirectory.resolve("settings.json");
        var mapper = JsonMapper.builder().build();
        var store = new LayoutSettingsStore(mapper, path);

        assertThat(store.current()).isEmpty();
        var saved = store.save(0, layout("dark", "account-1"));

        assertThat(saved.changed()).isTrue();
        assertThat(saved.conflict()).isFalse();
        assertThat(saved.snapshot().revision()).isEqualTo(1);
        assertThat(new LayoutSettingsStore(mapper, path).current())
                .contains(saved.snapshot());
    }

    @Test
    void rejectsStaleWritersAndInvalidLayoutsWithoutOverwritingTheCurrentRevision() {
        var store = new LayoutSettingsStore(
                JsonMapper.builder().build(), temporaryDirectory.resolve("settings.json"));
        var first = store.save(0, layout("dark", null));

        var conflict = store.save(0, layout("light", null));

        assertThat(conflict.conflict()).isTrue();
        assertThat(conflict.snapshot()).isEqualTo(first.snapshot());
        assertThat(store.current()).contains(first.snapshot());
        assertThatThrownBy(() -> store.save(
                        first.snapshot().revision(),
                        new LayoutSettings(
                                999,
                                List.of(),
                                List.of("home"),
                                "ja",
                                "dark",
                                null,
                                "relevance",
                                display(),
                                List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("レイアウト設定版");
        assertThat(store.current()).contains(first.snapshot());
    }

    @Test
    void migratesVersionSevenToTheDefaultReplyOrder() {
        var current = layout("dark", null);
        var legacy = new LayoutSettings(
                7,
                current.columns(),
                current.navItems(),
                current.locale(),
                current.theme(),
                current.activeAccountId(),
                null,
                current.display(),
                current.trendSearchHistory());

        var migrated = LayoutSettingsValidator.validateAndCopy(legacy);

        assertThat(migrated.version()).isEqualTo(8);
        assertThat(migrated.replySort()).isEqualTo("relevance");
    }

    @Test
    void loadsAStoredVersionSevenLayoutWithoutLosingExistingSettings() throws Exception {
        var path = temporaryDirectory.resolve("settings.json");
        Files.writeString(path, """
                {"schemaVersion":1,"revision":4,"layout":{"version":7,
                "columns":[{"id":"home-1","kind":"home","target":null,"label":null}],
                "navItems":["compose","home"],"locale":"ja","theme":"dark",
                "activeAccountId":"account-1","display":{"fontSize":"default",
                "accentColor":"purple","density":"comfortable","reduceMotion":false,
                "mediaPreview":true,"videoAutoplay":false,"videoLoop":true,
                "videoVolume":100,"autoTranslatePosts":true},"trendSearchHistory":["AI"]}}
                """);

        var loaded = new LayoutSettingsStore(JsonMapper.builder().build(), path);

        assertThat(loaded.current()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.revision()).isEqualTo(4);
            assertThat(snapshot.layout().version()).isEqualTo(8);
            assertThat(snapshot.layout().replySort()).isEqualTo("relevance");
            assertThat(snapshot.layout().activeAccountId()).isEqualTo("account-1");
            assertThat(snapshot.layout().display().accentColor()).isEqualTo("purple");
            assertThat(snapshot.layout().trendSearchHistory()).containsExactly("AI");
        });
    }

    @Test
    void migratesAllOlderLayoutGenerationsWithoutDiscardingColumns() {
        for (var version = 1; version <= 6; version++) {
            var display = version <= 2
                    ? null
                    : new LayoutSettings.Display(
                            "large",
                            "purple",
                            "compact",
                            true,
                            false,
                            true,
                            null,
                            null,
                            version >= 5 ? false : null);
            var migrated = LayoutSettingsValidator.validateAndCopy(new LayoutSettings(
                    version,
                    List.of(new LayoutSettings.Column("saved-home", "home", null, null)),
                    List.of("home", "notifications"),
                    "ja",
                    "dark",
                    version >= 2 ? "account-1" : null,
                    null,
                    display,
                    version >= 6 ? List.of("NyTweetDeck") : null));

            assertThat(migrated.version()).isEqualTo(8);
            assertThat(migrated.columns()).extracting(LayoutSettings.Column::id)
                    .containsExactly("saved-home");
            assertThat(migrated.replySort()).isEqualTo("relevance");
            assertThat(migrated.display().videoLoop()).isTrue();
            assertThat(migrated.display().videoVolume()).isEqualTo(100);
            assertThat(migrated.display().autoTranslatePosts())
                    .isEqualTo(version >= 5 ? false : true);
            assertThat(migrated.trendSearchHistory())
                    .isEqualTo(version >= 6 ? List.of("NyTweetDeck") : List.of());
        }
    }

    @Test
    void rewritesAMigratedPrimaryAtomicallyAndKeepsTheOriginalAsBackup() throws Exception {
        var path = temporaryDirectory.resolve("settings.json");
        Files.writeString(path, """
                {"schemaVersion":1,"revision":12,"layout":{"version":3,
                "columns":[{"id":"saved-home","kind":"home","target":null}],
                "navItems":["home","notifications"],"locale":"ja","theme":"dark",
                "activeAccountId":"account-1","display":{"fontSize":"large",
                "accentColor":"purple","density":"compact","reduceMotion":true,
                "mediaPreview":false,"videoAutoplay":true}}}
                """);

        var loaded = new LayoutSettingsStore(JsonMapper.builder().build(), path);

        assertThat(loaded.current()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.revision()).isEqualTo(12);
            assertThat(snapshot.layout().columns()).extracting(LayoutSettings.Column::id)
                    .containsExactly("saved-home");
        });
        assertThat(Files.readString(path)).contains("\"version\":8");
        assertThat(Files.readString(path.resolveSibling("settings.json.bak")))
                .contains("\"version\":3");
    }

    @Test
    void recoversThePreviousAtomicBackupWhenTheCurrentFileIsCorrupted() throws Exception {
        var path = temporaryDirectory.resolve("settings.json");
        var mapper = JsonMapper.builder().build();
        var store = new LayoutSettingsStore(mapper, path);
        store.save(0, layout("dark", null));
        store.save(1, layout("light", null));
        Files.writeString(path, "corrupted-layout-marker");

        var recovered = new LayoutSettingsStore(mapper, path);

        assertThat(recovered.current()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.revision()).isEqualTo(1);
            assertThat(snapshot.layout().theme()).isEqualTo("dark");
        });
        assertThat(Files.readString(path)).doesNotContain("corrupted-layout-marker");
    }

    @Test
    void recoversTheBackupWhenTheCurrentFileIsMissingAfterTheFirstSave() throws Exception {
        var path = temporaryDirectory.resolve("settings.json");
        var mapper = JsonMapper.builder().build();
        var store = new LayoutSettingsStore(mapper, path);
        store.save(0, layout("dark", null));
        Files.delete(path);

        var recovered = new LayoutSettingsStore(mapper, path);

        assertThat(recovered.current()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.revision()).isEqualTo(1);
            assertThat(snapshot.layout().theme()).isEqualTo("dark");
        });
        assertThat(new LayoutSettingsStore(mapper, path).current()).isEqualTo(recovered.current());
    }

    @Test
    void reinitializesWhenTheOnlySharedSettingsFileIsCorrupted() throws Exception {
        var path = temporaryDirectory.resolve("settings.json");
        var mapper = JsonMapper.builder().build();
        var store = new LayoutSettingsStore(mapper, path);
        store.save(0, layout("dark", null));
        Files.delete(path.resolveSibling("settings.json.bak"));
        Files.writeString(path, "corrupted-layout-marker");

        var recovered = new LayoutSettingsStore(mapper, path);

        assertThat(recovered.current()).isEmpty();
        var initialized = recovered.save(0, layout("light", null));
        assertThat(initialized.snapshot().revision()).isEqualTo(1);
        assertThat(new LayoutSettingsStore(mapper, path).current()).contains(initialized.snapshot());
        Files.writeString(path, "corrupted-again-marker");
        assertThat(new LayoutSettingsStore(mapper, path).current()).contains(initialized.snapshot());
    }

    @Test
    void reinitializesWhenBothSharedSettingsFilesAreCorrupted() throws Exception {
        var path = temporaryDirectory.resolve("settings.json");
        var backup = path.resolveSibling("settings.json.bak");
        var mapper = JsonMapper.builder().build();
        var store = new LayoutSettingsStore(mapper, path);
        store.save(0, layout("dark", null));
        store.save(1, layout("light", null));
        Files.writeString(path, "corrupted-current-marker");
        Files.writeString(backup, "corrupted-backup-marker");

        var recovered = new LayoutSettingsStore(mapper, path);

        assertThat(recovered.current()).isEmpty();
        var initialized = recovered.save(0, layout("dark", null));
        assertThat(new LayoutSettingsStore(mapper, path).current()).contains(initialized.snapshot());
        assertThat(Files.readString(backup))
                .doesNotContain("corrupted-backup-marker")
                .contains("\"revision\":1");
    }

    @Test
    void restrictsTheSharedSettingsFileToTheOwnerOnPosixSystems() throws Exception {
        var path = temporaryDirectory.resolve("settings.json");
        var store = new LayoutSettingsStore(JsonMapper.builder().build(), path);
        store.save(0, layout("dark", null));

        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(path))
                    .isEqualTo(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
        }
    }

    static LayoutSettings layout(String theme, String activeAccountId) {
        return new LayoutSettings(
                8,
                List.of(new LayoutSettings.Column("home-1", "home", null, null)),
                List.of("compose", "home"),
                "ja",
                theme,
                activeAccountId,
                "relevance",
                display(),
                List.of("NyTweetDeck"));
    }

    private static LayoutSettings.Display display() {
        return new LayoutSettings.Display(
                "default", "blue", "comfortable", false, true, false, true, 100, true);
    }
}
