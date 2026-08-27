package dev.nytweetdeck.account;

import dev.nytweetdeck.system.ApplicationDataPaths;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountStore.class);
    static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_ACCOUNTS = 100;

    private final ObjectMapper objectMapper;
    private final Path storePath;
    private List<AccountSecrets> accounts;

    @Autowired
    public AccountStore(
            ObjectMapper objectMapper,
            @Value("${nytweetdeck.account.store-path:}") String configuredStorePath) {
        this(
                objectMapper,
                ApplicationDataPaths.resolve(configuredStorePath, "accounts.json"),
                configuredStorePath == null || configuredStorePath.isBlank()
                        ? ApplicationDataPaths.legacyCandidates("accounts.json", AccountStore.class)
                        : List.of());
    }

    public AccountStore(ObjectMapper objectMapper, Path storePath) {
        this(objectMapper, storePath, List.of());
    }

    AccountStore(ObjectMapper objectMapper, Path storePath, List<Path> legacyCandidates) {
        this.objectMapper = objectMapper;
        this.storePath = storePath.toAbsolutePath().normalize();
        this.accounts = loadInitial(legacyCandidates);
    }

    public synchronized List<AccountSummary> accountSummaries() {
        return accounts.stream()
                .map(account -> new AccountSummary(
                        account.accountId(),
                        account.userId(),
                        account.username(),
                        account.displayName()))
                .toList();
    }

    public synchronized void addOrReplace(AccountSecrets account) {
        var updated = new ArrayList<AccountSecrets>();
        for (var existing : accounts) {
            if (!existing.accountId().equals(account.accountId())) {
                updated.add(existing);
            }
        }
        updated.add(account);
        save(updated);
        accounts = List.copyOf(updated);
    }

    public synchronized AccountSecrets requireAccount(String accountId) {
        return accounts.stream()
                .filter(account -> account.accountId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("指定したアカウントがありません。"));
    }

    private List<AccountSecrets> loadInitial(List<Path> legacyCandidates) {
        if (Files.isRegularFile(storePath)) {
            return loadWithRecovery();
        }
        AccountStoreException firstFailure = null;
        var candidates = legacyCandidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> !path.equals(storePath))
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(AccountStore::lastModified).reversed())
                .toList();
        for (var candidate : candidates) {
            try {
                var migrated = load(candidate);
                save(migrated);
                LOGGER.info(
                        "旧アカウント保存データをユーザー別保存先へ移行しました: source={}",
                        candidate);
                return migrated;
            } catch (AccountStoreException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw new AccountStoreException("旧アカウント保存データを移行できません。", firstFailure);
        }
        return List.of();
    }

    private List<AccountSecrets> loadWithRecovery() {
        if (!Files.isRegularFile(storePath)) {
            return List.of();
        }
        try {
            return load(storePath);
        } catch (AccountStoreException primaryFailure) {
            var backup = backupPath();
            if (!Files.isRegularFile(backup)) {
                throw primaryFailure;
            }
            var recovered = load(backup);
            try {
                Files.copy(backup, storePath, StandardCopyOption.REPLACE_EXISTING);
                restrictToOwner(storePath);
            } catch (IOException exception) {
                throw new AccountStoreException("アカウント保存データを復旧できません。", exception);
            }
            return recovered;
        }
    }

    private List<AccountSecrets> load(Path path) {
        try {
            var document = objectMapper.readValue(path.toFile(), AccountDocument.class);
            if (document == null) {
                throw new IllegalArgumentException("アカウント保存データが空です。");
            }
            if (document.schemaVersion() != CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "未対応のアカウント保存データ版です: " + document.schemaVersion());
            }
            return validateAccounts(document.accounts());
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new AccountStoreException("アカウント保存データを読み込めません。", exception);
        }
    }

    private void save(List<AccountSecrets> updated) {
        var validated = validateAccounts(updated);
        try {
            var parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isRegularFile(storePath)) {
                Files.copy(storePath, backupPath(), StandardCopyOption.REPLACE_EXISTING);
                restrictToOwner(backupPath());
            }
            var temporary = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            objectMapper.writeValue(
                    temporary.toFile(),
                    new AccountDocument(CURRENT_SCHEMA_VERSION, validated));
            restrictToOwner(temporary);
            try {
                Files.move(
                        temporary,
                        storePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, storePath, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(storePath);
        } catch (IOException exception) {
            throw new AccountStoreException("アカウント保存データを書き込めません。", exception);
        }
    }

    private Path backupPath() {
        return storePath.resolveSibling(storePath.getFileName() + ".bak");
    }

    private static java.nio.file.attribute.FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    private static List<AccountSecrets> validateAccounts(List<AccountSecrets> accounts) {
        if (accounts == null || accounts.size() > MAX_ACCOUNTS) {
            throw new IllegalArgumentException("保存できるアカウントは100件以下です。");
        }
        var ids = new HashSet<String>();
        for (var account : accounts) {
            if (account == null || !ids.add(account.accountId())) {
                throw new IllegalArgumentException("アカウント保存データに重複または空要素があります。");
            }
        }
        return List.copyOf(accounts);
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(
                    path,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows inherits the user's directory ACL; POSIX systems receive mode 600 above.
        } catch (IOException exception) {
            LOGGER.warn(
                    "アカウント保存ファイルの所有者限定権限を設定できません: file={}, cause={}",
                    path.getFileName(),
                    exception.getClass().getSimpleName());
        }
    }

    record AccountDocument(int schemaVersion, List<AccountSecrets> accounts) {
        AccountDocument {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }
    }

    public record AccountSummary(
            String accountId, String userId, String username, String displayName) {}
}
