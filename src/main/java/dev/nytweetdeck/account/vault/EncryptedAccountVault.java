package dev.nytweetdeck.account.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class EncryptedAccountVault {

    static final int CURRENT_SCHEMA_VERSION = 1;
    static final int PBKDF2_ITERATIONS = 600_000;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final byte[] AAD = "NyTweetDeck account vault:v1"
            .getBytes(StandardCharsets.UTF_8);

    private final Path vaultPath;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    @Autowired
    public EncryptedAccountVault(
            ObjectMapper objectMapper,
            @Value("${nytweetdeck.account.vault-path:.local/accounts.vault}") String vaultPath) {
        this(objectMapper, Path.of(vaultPath).toAbsolutePath().normalize(), new SecureRandom());
    }

    EncryptedAccountVault(ObjectMapper objectMapper, Path vaultPath, SecureRandom secureRandom) {
        this.objectMapper = objectMapper;
        this.vaultPath = vaultPath;
        this.secureRandom = secureRandom;
    }

    public boolean exists() {
        return Files.isRegularFile(vaultPath);
    }

    public synchronized void save(List<AccountSecrets> accounts, char[] passphrase) {
        validatePassphrase(passphrase);
        var salt = randomBytes(SALT_LENGTH);
        var iv = randomBytes(IV_LENGTH);
        byte[] plaintext = null;
        byte[] keyBytes = null;
        try {
            var payload = new VaultPayload(CURRENT_SCHEMA_VERSION, List.copyOf(accounts));
            plaintext = objectMapper.writeValueAsBytes(payload);
            keyBytes = deriveKey(passphrase, salt, PBKDF2_ITERATIONS);
            var cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            var ciphertext = cipher.doFinal(plaintext);
            var encoder = Base64.getEncoder();
            var envelope = new VaultEnvelope(
                    CURRENT_SCHEMA_VERSION,
                    KDF,
                    PBKDF2_ITERATIONS,
                    encoder.encodeToString(salt),
                    CIPHER,
                    encoder.encodeToString(iv),
                    encoder.encodeToString(ciphertext));
            writeEnvelope(envelope);
        } catch (JacksonException | GeneralSecurityException exception) {
            throw new VaultException("アカウントVaultを暗号化できません。", exception);
        } finally {
            clear(plaintext);
            clear(keyBytes);
        }
    }

    public synchronized List<AccountSecrets> load(char[] passphrase) {
        validatePassphrase(passphrase);
        if (!exists()) {
            return List.of();
        }
        return loadFrom(vaultPath, passphrase);
    }

    public synchronized List<AccountSecrets> recoverFromBackup(char[] passphrase) {
        validatePassphrase(passphrase);
        var backupPath = backupPath();
        if (!Files.isRegularFile(backupPath)) {
            throw new VaultException("復旧可能なアカウントVaultバックアップがありません。");
        }
        var accounts = loadFrom(backupPath, passphrase);
        try {
            Files.copy(backupPath, vaultPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new VaultException("アカウントVaultバックアップを復元できません。", exception);
        }
        return accounts;
    }

    public Path vaultPath() {
        return vaultPath;
    }

    private List<AccountSecrets> loadFrom(Path source, char[] passphrase) {
        byte[] keyBytes = null;
        byte[] plaintext = null;
        try {
            var envelope = objectMapper.readValue(source.toFile(), VaultEnvelope.class);
            validateEnvelope(envelope);
            var decoder = Base64.getDecoder();
            var salt = decoder.decode(envelope.salt());
            var iv = decoder.decode(envelope.iv());
            var ciphertext = decoder.decode(envelope.ciphertext());
            if (salt.length != SALT_LENGTH || iv.length != IV_LENGTH) {
                throw new VaultException("アカウントVaultのsaltまたはIV長が不正です。");
            }
            keyBytes = deriveKey(passphrase, salt, envelope.iterations());
            var cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            plaintext = cipher.doFinal(ciphertext);
            var payload = objectMapper.readValue(plaintext, VaultPayload.class);
            if (payload.schemaVersion() != CURRENT_SCHEMA_VERSION) {
                throw new VaultException(
                        "未対応のアカウントVaultデータ版です: " + payload.schemaVersion());
            }
            return List.copyOf(payload.accounts());
        } catch (AEADBadTagException exception) {
            throw new VaultException("Vaultパスフレーズが違うか、データが破損しています。");
        } catch (JacksonException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new VaultException("アカウントVaultを復号できません。", exception);
        } finally {
            clear(keyBytes);
            clear(plaintext);
        }
    }

    private void writeEnvelope(VaultEnvelope envelope) {
        try {
            var parent = vaultPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isRegularFile(vaultPath)) {
                Files.copy(vaultPath, backupPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            var temporaryPath = vaultPath.resolveSibling(vaultPath.getFileName() + ".tmp");
            objectMapper.writeValue(temporaryPath.toFile(), envelope);
            try {
                Files.move(
                        temporaryPath,
                        vaultPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, vaultPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new VaultException("アカウントVaultを書き込めません。", exception);
        }
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        var specification = new PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(specification).getEncoded();
        } finally {
            specification.clearPassword();
        }
    }

    private void validateEnvelope(VaultEnvelope envelope) {
        if (envelope.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            throw new VaultException(
                    "未対応のアカウントVault形式です: " + envelope.schemaVersion());
        }
        if (!KDF.equals(envelope.kdf()) || !CIPHER.equals(envelope.cipher())) {
            throw new VaultException("アカウントVaultの暗号方式が未対応です。");
        }
        if (envelope.iterations() < PBKDF2_ITERATIONS) {
            throw new VaultException("アカウントVaultのKDF反復回数が安全基準未満です。");
        }
    }

    private static void validatePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < 12 || passphrase.length > 1024) {
            throw new IllegalArgumentException("Vaultパスフレーズは12〜1024文字で指定してください。");
        }
    }

    private byte[] randomBytes(int length) {
        var bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private Path backupPath() {
        return vaultPath.resolveSibling(vaultPath.getFileName() + ".bak");
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private record VaultPayload(int schemaVersion, List<AccountSecrets> accounts) {}

    private record VaultEnvelope(
            int schemaVersion,
            String kdf,
            int iterations,
            String salt,
            String cipher,
            String iv,
            String ciphertext) {}
}
