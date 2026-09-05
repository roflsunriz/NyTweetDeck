package dev.nytweetdeck.update;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DesktopReleaseService {

    private static final URI RELEASES_URI = URI.create(
            "https://api.github.com/repos/roflsunriz/NyTweetDeck/releases?per_page=100");
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern DESKTOP_TAG = Pattern.compile(
            "v((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*))");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI releasesUri;
    private final String currentVersion;

    @Autowired
    public DesktopReleaseService(HttpClient httpClient, ObjectMapper objectMapper,
            ObjectProvider<BuildProperties> buildProperties) {
        this(httpClient, objectMapper, RELEASES_URI,
                buildProperties.getIfAvailable() == null ? null : buildProperties.getObject().getVersion());
    }

    DesktopReleaseService(HttpClient httpClient, ObjectMapper objectMapper, URI releasesUri,
            String currentVersion) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.releasesUri = releasesUri;
        this.currentVersion = currentVersion;
    }

    public DesktopRelease latestStableRelease() {
        // SNAPSHOT builds use their base version, so the same stable base is not offered again.
        var installed = stableVersion(currentVersion == null ? ""
                : "v" + currentVersion.replaceFirst("-SNAPSHOT$", ""));
        if (installed == null) {
            throw new IllegalStateException("The running desktop build version could not be determined");
        }
        var request = HttpRequest.newBuilder(releasesUri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "NyTweetDeck-Desktop")
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GitHub Releases API returned HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var body = response.body()) {
                bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("GitHub Releases API response is too large");
            }
            var releases = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (!releases.isArray()) {
                throw new IOException("GitHub Releases API response is not an array");
            }
            DesktopRelease latest = null;
            for (var release : releases) {
                var candidate = releaseAsset(release).orElse(null);
                if (candidate != null
                        && (latest == null || stableVersion(candidate.tagName())
                                .compareTo(stableVersion(latest.tagName())) > 0)) {
                    latest = candidate;
                }
            }
            if (latest == null) {
                throw new IOException("A stable desktop release was not found");
            }
            return new DesktopRelease(latest.tagName(), latest.assetName(), latest.downloadUrl(),
                    latest.sizeBytes(), currentVersion,
                    stableVersion(latest.tagName()).compareTo(installed) > 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub Releases API request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("GitHub Releases API request failed", exception);
        }
    }

    private java.util.Optional<DesktopRelease> releaseAsset(JsonNode release) {
        if (!release.isObject()
                || booleanValue(release, "draft")
                || booleanValue(release, "prerelease")) {
            return java.util.Optional.empty();
        }
        var tagName = text(release, "tag_name");
        var matcher = DESKTOP_TAG.matcher(tagName);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }
        var expectedAssetName = "NyTweetDeck-v" + matcher.group(1) + ".zip";
        for (var asset : release.path("assets")) {
            if (!expectedAssetName.equals(text(asset, "name"))
                    || !"uploaded".equals(text(asset, "state"))) {
                continue;
            }
            var downloadUrl = verifiedDownloadUrl(
                    text(asset, "browser_download_url"), tagName, expectedAssetName);
            if (downloadUrl == null) {
                continue;
            }
            var size = asset.path("size").canConvertToLong()
                    ? asset.path("size").longValue()
                    : null;
            return java.util.Optional.of(new DesktopRelease(
                    tagName, expectedAssetName, downloadUrl, size, currentVersion, false));
        }
        return java.util.Optional.empty();
    }

    private static String verifiedDownloadUrl(String value, String tagName, String assetName) {
        try {
            var uri = URI.create(value);
            var expectedPath = "/roflsunriz/NyTweetDeck/releases/download/"
                    + tagName + "/" + assetName;
            return "https".equalsIgnoreCase(uri.getScheme())
                            && "github.com".equalsIgnoreCase(uri.getHost())
                            && uri.getUserInfo() == null
                            && (uri.getPort() == -1 || uri.getPort() == 443)
                            && expectedPath.equals(uri.getRawPath())
                            && uri.getRawQuery() == null
                            && uri.getRawFragment() == null
                    ? uri.toASCIIString()
                    : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Version stableVersion(String tag) {
        var matcher = DESKTOP_TAG.matcher(tag);
        if (!matcher.matches()) return null;
        var parts = matcher.group(1).split("\\.");
        return new Version(new BigInteger(parts[0]), new BigInteger(parts[1]), new BigInteger(parts[2]));
    }

    private static boolean booleanValue(JsonNode parent, String name) {
        var value = parent.get(name);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static String text(JsonNode parent, String name) {
        var value = parent.get(name);
        return value != null && value.isString() ? value.asString("") : "";
    }

    public record DesktopRelease(
            String tagName,
            String assetName,
            String downloadUrl,
            Long sizeBytes,
            String currentVersion,
            boolean updateAvailable) {}

    private record Version(BigInteger major, BigInteger minor, BigInteger patch) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int result = major.compareTo(other.major);
            if (result == 0) result = minor.compareTo(other.minor);
            return result == 0 ? patch.compareTo(other.patch) : result;
        }
    }
}
