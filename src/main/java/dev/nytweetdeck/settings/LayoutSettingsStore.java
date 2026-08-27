package dev.nytweetdeck.settings;

import dev.nytweetdeck.system.ApplicationDataPaths;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LayoutSettingsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(LayoutSettingsStore.class);
    static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final Path storePath;
    private Snapshot current;

    @Autowired
    public LayoutSettingsStore(
            ObjectMapper objectMapper,
            @Value("${nytweetdeck.settings.store-path:}") String configuredStorePath) {
        this(
                objectMapper,
                ApplicationDataPaths.resolve(configuredStorePath, "settings.json"));
    }

    public LayoutSettingsStore(ObjectMapper objectMapper, Path storePath) {
        this.objectMapper = objectMapper;
        this.storePath = storePath.toAbsolutePath().normalize();
        this.current = loadWithRecovery();
    }

    public synchronized Optional<Snapshot> current() {
        return Optional.ofNullable(current);
    }

    public synchronized SaveResult save(long expectedRevision, LayoutSettings layout) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("設定リビジョンが不正です。");
        }
        var validated = LayoutSettingsValidator.validateAndCopy(layout);
        var currentRevision = current == null ? 0L : current.revision();
        if (expectedRevision != currentRevision) {
            return new SaveResult(current, false, true);
        }
        if (current != null && current.layout().equals(validated)) {
            return new SaveResult(current, false, false);
        }
        var updated = new Snapshot(currentRevision + 1, validated);
        write(updated);
        current = updated;
        return new SaveResult(updated, true, false);
    }

    private Snapshot loadWithRecovery() {
        if (!Files.isRegularFile(storePath)) {
            return null;
        }
        try {
            return load(storePath);
        } catch (LayoutSettingsStoreException primaryFailure) {
            var backup = backupPath();
            if (!Files.isRegularFile(backup)) {
                throw primaryFailure;
            }
            var recovered = load(backup);
            try {
                Files.copy(backup, storePath, StandardCopyOption.REPLACE_EXISTING);
                restrictToOwner(storePath);
            } catch (IOException exception) {
                throw new LayoutSettingsStoreException("共有設定を復旧できません。", exception);
            }
            return recovered;
        }
    }

    private Snapshot load(Path path) {
        try {
            var document = objectMapper.readValue(path.toFile(), StoreDocument.class);
            if (document == null || document.schemaVersion() != CURRENT_SCHEMA_VERSION
                    || document.revision() < 1) {
                throw new IllegalArgumentException("未対応の共有設定保存版です。");
            }
            return new Snapshot(
                    document.revision(),
                    LayoutSettingsValidator.validateAndCopy(document.layout()));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new LayoutSettingsStoreException("共有設定を読み込めません。", exception);
        }
    }

    private void write(Snapshot snapshot) {
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
                    new StoreDocument(
                            CURRENT_SCHEMA_VERSION, snapshot.revision(), snapshot.layout()));
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
            throw new LayoutSettingsStoreException("共有設定を書き込めません。", exception);
        }
    }

    private Path backupPath() {
        return storePath.resolveSibling(storePath.getFileName() + ".bak");
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
                    "共有設定ファイルの所有者限定権限を設定できません: file={}, cause={}",
                    path.getFileName(),
                    exception.getClass().getSimpleName());
        }
    }

    record StoreDocument(int schemaVersion, long revision, LayoutSettings layout) {}

    public record Snapshot(long revision, LayoutSettings layout) {}

    public record SaveResult(Snapshot snapshot, boolean changed, boolean conflict) {}
}
