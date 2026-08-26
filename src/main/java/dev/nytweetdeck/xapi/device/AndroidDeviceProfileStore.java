package dev.nytweetdeck.xapi.device;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AndroidDeviceProfileStore {

    private final Path profilePath;
    private final ObjectMapper objectMapper;

    public AndroidDeviceProfileStore(
            ObjectMapper objectMapper,
            @Value("${nytweetdeck.x-api.device-profile-path:.local/android-device.json}")
                    String profilePath) {
        this.objectMapper = objectMapper;
        this.profilePath = Path.of(profilePath).toAbsolutePath().normalize();
    }

    public Optional<AndroidDeviceProfile> find() {
        if (!Files.isRegularFile(profilePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(profilePath.toFile(), AndroidDeviceProfile.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Android端末プロファイルが破損しています。設定画面から再作成してください。",
                    exception);
        }
    }

    public AndroidDeviceProfile require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "Android端末プロファイルが未設定です。設定画面から作成してください。"));
    }

    public synchronized void save(AndroidDeviceProfile profile) {
        try {
            var parent = profilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var temporaryPath = profilePath.resolveSibling(profilePath.getFileName() + ".tmp");
            objectMapper.writeValue(temporaryPath.toFile(), profile);
            try {
                Files.move(
                        temporaryPath,
                        profilePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, profilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Android端末プロファイルを保存できません。", exception);
        }
    }

    public Path profilePath() {
        return profilePath;
    }
}
