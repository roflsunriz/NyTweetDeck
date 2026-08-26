package dev.nytweetdeck.account.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class EncryptedAccountVaultTest {

    @TempDir
    Path temporaryDirectory;

    private final char[] passphrase = "correct horse battery staple".toCharArray();

    @Test
    void encryptsAndDecryptsMultipleAccountsWithoutPlaintextLeakage() throws Exception {
        var path = temporaryDirectory.resolve("accounts.vault");
        var vault = createVault(path);
        var accounts = List.of(
                account(
                        "1",
                        "plaintext-alice-account-marker",
                        "plaintext-alice-token-marker",
                        "plaintext-alice-secret-marker"),
                account(
                        "2",
                        "plaintext-bob-account-marker",
                        "plaintext-bob-token-marker",
                        "plaintext-bob-secret-marker"));

        vault.save(accounts, passphrase);
        var loaded = vault.load(passphrase);

        assertThat(loaded).isEqualTo(accounts);
        var stored = Files.readString(path);
        assertThat(stored)
                .doesNotContain(
                        "plaintext-alice-account-marker",
                        "plaintext-bob-account-marker",
                        "plaintext-alice-token-marker",
                        "plaintext-bob-secret-marker")
                .contains("PBKDF2WithHmacSHA256", "AES/GCM/NoPadding", "600000");
        assertThat(accounts.get(0).toString())
                .doesNotContain(
                        "plaintext-alice-token-marker", "plaintext-alice-secret-marker");
    }

    @Test
    void rejectsWrongPassphraseWithoutLeakingCiphertext() {
        var vault = createVault(temporaryDirectory.resolve("accounts.vault"));
        vault.save(List.of(account("1", "alice", "token", "secret")), passphrase);

        assertThatThrownBy(() -> vault.load("wrong passphrase value".toCharArray()))
                .isInstanceOf(VaultException.class)
                .hasMessage("Vaultパスフレーズが違うか、データが破損しています。")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("secret");
    }

    @Test
    void createsAndRecoversPreviousBackup() throws Exception {
        var path = temporaryDirectory.resolve("accounts.vault");
        var vault = createVault(path);
        var original = List.of(account("1", "alice", "token-a", "secret-a"));
        var updated = List.of(account("2", "bob", "token-b", "secret-b"));
        vault.save(original, passphrase);
        vault.save(updated, passphrase);
        Files.writeString(path, "corrupted");

        var recovered = vault.recoverFromBackup(passphrase);

        assertThat(recovered).isEqualTo(original);
        assertThat(vault.load(passphrase)).isEqualTo(original);
    }

    @Test
    void enforcesMinimumPassphraseLength() {
        var vault = createVault(temporaryDirectory.resolve("accounts.vault"));

        assertThatThrownBy(() -> vault.save(List.of(), "too-short".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12〜1024");
    }

    private EncryptedAccountVault createVault(Path path) {
        return new EncryptedAccountVault(
                JsonMapper.builder().build(), path, new SecureRandom(new byte[] {1, 2, 3, 4}));
    }

    private static AccountSecrets account(
            String id, String username, String token, String tokenSecret) {
        return AccountSecrets.webSession(id, id, username, username, token, tokenSecret, "csrf-" + id);
    }
}
