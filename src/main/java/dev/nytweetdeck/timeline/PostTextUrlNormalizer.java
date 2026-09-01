package dev.nytweetdeck.timeline;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import dev.nytweetdeck.timeline.TimelinePage.TextLink;

final class PostTextUrlNormalizer {

    enum Kind {
        LINK,
        MEDIA,
        ARTICLE
    }

    record UrlEntity(
            String shortUrl,
            String displayUrl,
            String expandedUrl,
            String unwoundUrl,
            Kind kind) {

        UrlEntity(String shortUrl, String expandedUrl, String unwoundUrl, Kind kind) {
            this(shortUrl, null, expandedUrl, unwoundUrl, kind);
        }
    }

    private PostTextUrlNormalizer() {}

    static String normalize(String value, List<UrlEntity> entities) {
        if (value == null || entities.isEmpty()) {
            return value;
        }
        var replacements = new LinkedHashMap<String, String>();
        for (var entity : entities) {
            if (!isTcoUrl(entity.shortUrl())) {
                continue;
            }
            if (entity.kind() != Kind.LINK) {
                replacements.put(entity.shortUrl(), "");
                continue;
            }
            var destination = firstHttpUrl(entity.unwoundUrl(), entity.expandedUrl());
            if (destination != null && !isTcoUrl(destination)) {
                replacements.putIfAbsent(entity.shortUrl(), destination);
            }
        }
        var normalized = value;
        var removedContent = false;
        for (var replacement : replacements.entrySet()) {
            normalized = normalized.replace(replacement.getKey(), replacement.getValue());
            removedContent |= replacement.getValue().isEmpty();
        }
        if (!removedContent) {
            return normalized;
        }
        return normalized
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+(?=\\R|$)", "")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("\\R{3,}", "\n\n")
                .strip();
    }

    static List<TextLink> links(List<UrlEntity> entities) {
        var links = new LinkedHashMap<String, TextLink>();
        for (var entity : entities) {
            if (entity.kind() != Kind.LINK) {
                continue;
            }
            var destination = firstHttpUrl(
                    entity.unwoundUrl(), entity.expandedUrl(), entity.shortUrl());
            if (destination == null) {
                continue;
            }
            var display = entity.displayUrl();
            if (display == null || display.isBlank()) {
                display = destination;
            }
            links.putIfAbsent(destination + "\0" + display, new TextLink(destination, display));
        }
        return List.copyOf(links.values());
    }

    private static String firstHttpUrl(String... candidates) {
        for (var candidate : candidates) {
            if (isHttpUrl(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isTcoUrl(String value) {
        if (!isHttpUrl(value)) {
            return false;
        }
        return "t.co".equalsIgnoreCase(URI.create(value).getHost());
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            var uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
