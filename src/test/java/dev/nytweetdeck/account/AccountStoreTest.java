package dev.nytweetdeck.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AccountStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsWithoutSetupAndAutomaticallyLoadsSavedAccountsAfterRestart() throws Exception {
        var path = temporaryDirectory.resolve("accounts.json");
        var mapper = JsonMapper.builder().build();
        var store = new AccountStore(mapper, path);

        assertThat(store.accountSummaries()).isEmpty();
        store.addOrReplace(account("1", "alice", "token-a", "auth-a"));

        var restarted = new AccountStore(mapper, path);
        assertThat(restarted.accountSummaries()).containsExactly(
                new AccountStore.AccountSummary("1", "1", "alice", "alice"));
        assertThat(restarted.requireAccount("1").authToken()).isEqualTo("auth-a");
        assertThat(restarted.accountSummaries().toString())
                .doesNotContain("token-a", "auth-a", "csrf-1");
        assertThat(restarted.requireAccount("1").toString())
                .doesNotContain("token-a", "auth-a", "csrf-1");
        assertThat(Files.readString(path)).contains("token-a", "auth-a", "csrf-1");
    }

    @Test
    void replacesAnAccountAtomicallyAndRecoversThePreviousBackup() throws Exception {
        var path = temporaryDirectory.resolve("accounts.json");
        var mapper = JsonMapper.builder().build();
        var store = new AccountStore(mapper, path);
        store.addOrReplace(account("1", "alice", "token-a", "auth-a"));
        store.addOrReplace(account("1", "alice-new", "token-b", "auth-b"));
        Files.writeString(path, "corrupted");

        var recovered = new AccountStore(mapper, path);

        assertThat(recovered.requireAccount("1").username()).isEqualTo("alice");
        assertThat(new AccountStore(mapper, path).requireAccount("1").username())
                .isEqualTo("alice");
    }

    @Test
    void reportsMissingAccountsAndUnrecoverableDataWithoutLeakingSecrets() throws Exception {
        var path = temporaryDirectory.resolve("accounts.json");
        var store = new AccountStore(JsonMapper.builder().build(), path);

        assertThatThrownBy(() -> store.requireAccount("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("指定したアカウント");
        Files.writeString(path, "corrupted-secret-marker");
        assertThatThrownBy(() -> new AccountStore(JsonMapper.builder().build(), path))
                .isInstanceOf(AccountStoreException.class)
                .hasMessageContaining("読み込めません")
                .hasMessageNotContaining("corrupted-secret-marker");
    }

    @Test
    void restrictsTheStoreToTheOwnerOnPosixSystems() throws Exception {
        var path = temporaryDirectory.resolve("accounts.json");
        var store = new AccountStore(JsonMapper.builder().build(), path);
        store.addOrReplace(account("1", "alice", "token-a", "auth-a"));

        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(path))
                    .isEqualTo(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void migratesTheNewestValidWorkingDirectoryStoreWithoutDeletingTheSource() throws Exception {
        var mapper = JsonMapper.builder().build();
        var older = temporaryDirectory.resolve("project/.local/accounts.json");
        var newer = temporaryDirectory.resolve("project/target/.local/accounts.json");
        var canonical = temporaryDirectory.resolve("user-data/NyTweetDeck/accounts.json");
        new AccountStore(mapper, older)
                .addOrReplace(account("1", "older", "token-old", "auth-old"));
        new AccountStore(mapper, newer)
                .addOrReplace(account("1", "newer", "token-new", "auth-new"));
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));

        var migrated = new AccountStore(mapper, canonical, List.of(older, newer));

        assertThat(migrated.requireAccount("1").username()).isEqualTo("newer");
        assertThat(Files.isRegularFile(canonical)).isTrue();
        assertThat(Files.isRegularFile(older)).isTrue();
        assertThat(Files.isRegularFile(newer)).isTrue();
        assertThat(new AccountStore(mapper, canonical).requireAccount("1").authToken())
                .isEqualTo("auth-new");
    }

    private static AccountSecrets account(
            String id, String username, String bearer, String authToken) {
        return AccountSecrets.webSession(
                id, id, username, username, bearer, authToken, "csrf-" + id);
    }
}
