package dev.nytweetdeck.system;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class ApplicationDataPaths {

    private static final String APPLICATION_DIRECTORY_NAME = "NyTweetDeck";

    private ApplicationDataPaths() {}

    public static Path resolve(String configuredPath, String fileName) {
        return resolve(
                configuredPath,
                fileName,
                System.getenv(),
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")),
                workingDirectory());
    }

    public static List<Path> legacyCandidates(String fileName, Class<?> anchor) {
        var working = workingDirectory();
        var locations = new LinkedHashSet<Path>();
        locations.add(working.resolve(".local").resolve(fileName));
        locations.add(working.resolve("target").resolve(".local").resolve(fileName));
        if (working.getParent() != null) {
            locations.add(working.getParent().resolve(".local").resolve(fileName));
        }
        var codeLocation = codeLocation(anchor);
        if (codeLocation != null) {
            var applicationDirectory = Files.isRegularFile(codeLocation)
                    ? codeLocation.getParent()
                    : codeLocation;
            if (applicationDirectory != null) {
                locations.add(applicationDirectory.resolve(".local").resolve(fileName));
                if (applicationDirectory.getParent() != null) {
                    locations.add(
                            applicationDirectory.getParent().resolve(".local").resolve(fileName));
                }
            }
        }
        return new ArrayList<>(locations).stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    static Path resolve(
            String configuredPath,
            String fileName,
            Map<String, String> environment,
            String operatingSystem,
            Path userHome,
            Path workingDirectory) {
        var normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (configuredPath != null && !configuredPath.isBlank()) {
            return normalizedWorkingDirectory.resolve(configuredPath).normalize();
        }
        var normalizedHome = userHome.toAbsolutePath().normalize();
        var os = operatingSystem.toLowerCase(java.util.Locale.ROOT);
        Path dataRoot;
        if (os.contains("win")) {
            var localAppData = environment.get("LOCALAPPDATA");
            dataRoot = localAppData == null || localAppData.isBlank()
                    ? normalizedHome.resolve("AppData/Local")
                    : Path.of(localAppData);
        } else if (os.contains("mac")) {
            dataRoot = normalizedHome.resolve("Library/Application Support");
        } else {
            var xdgDataHome = environment.get("XDG_DATA_HOME");
            dataRoot = xdgDataHome == null || xdgDataHome.isBlank()
                    ? normalizedHome.resolve(".local/share")
                    : Path.of(xdgDataHome);
        }
        return dataRoot
                .toAbsolutePath()
                .normalize()
                .resolve(APPLICATION_DIRECTORY_NAME)
                .resolve(fileName)
                .normalize();
    }

    private static Path codeLocation(Class<?> anchor) {
        try {
            var source = anchor.getProtectionDomain().getCodeSource();
            return source == null
                    ? null
                    : Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException exception) {
            return null;
        }
    }

    private static Path workingDirectory() {
        return Path.of("").toAbsolutePath().normalize();
    }
}
