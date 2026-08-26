package dev.nytweetdeck.account.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AccountVaultSessionManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsLocksUnlocksAndListsOnlyPublicAccountFields() {
        var manager = createManager();
        var passphrase = "correct horse battery staple".toCharArray();
        manager.create(passphrase);
        manager.addOrReplace(new AccountSecrets(
                "account-1", "42", "alice", "Alice", "oauth-token", "oauth-secret"));

        var summaries = manager.accountSummaries();
        manager.lock();

        assertThat(summaries).containsExactly(
                new AccountVaultSessionManager.AccountSummary("account-1", "42", "alice", "Alice"));
        assertThat(summaries.toString()).doesNotContain("oauth-token", "oauth-secret");
        assertThat(manager.status().unlocked()).isFalse();
        assertThatThrownBy(manager::accountSummaries)
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("ロック");

        manager.unlock(passphrase);
        assertThat(manager.status().accountCount()).isEqualTo(1);
        assertThat(manager.requireAccount("account-1").username()).isEqualTo("alice");
    }

    @Test
    void controllerClearsRequestPassphraseAfterUse() {
        var manager = createManager();
        var controller = new AccountVaultController(manager);
        var passphrase = "correct horse battery staple".toCharArray();

        controller.create(new AccountVaultController.PassphraseRequest(passphrase));

        assertThat(passphrase).containsOnly('\0');
        assertThat(controller.status().unlocked()).isTrue();
    }

    @Test
    void publishesAnEventAfterSecretsAreClearedOnLock() {
        var events = new ArrayList<Object>();
        var vault = new EncryptedAccountVault(
                JsonMapper.builder().build(),
                temporaryDirectory.resolve("event-accounts.vault"),
                new SecureRandom(new byte[] {1, 2, 3, 4}));
        var manager = new AccountVaultSessionManager(vault, events::add);
        manager.create("correct horse battery staple".toCharArray());

        manager.lock();

        assertThat(manager.status().unlocked()).isFalse();
        assertThat(events).singleElement().isInstanceOf(VaultLockedEvent.class);
    }

    private AccountVaultSessionManager createManager() {
        var vault = new EncryptedAccountVault(
                JsonMapper.builder().build(),
                temporaryDirectory.resolve("accounts.vault"),
                new SecureRandom(new byte[] {5, 6, 7, 8}));
        return new AccountVaultSessionManager(vault);
    }
}
