package dev.nytweetdeck.xapi.http;

public record AndroidDeviceIdentity(
        String userAgent,
        String clientUuid,
        String deviceId,
        String language,
        String securityPatchLevel) {

    public AndroidDeviceIdentity {
        requireValue(userAgent, "User-Agent");
        requireValue(clientUuid, "client UUID");
        requireValue(deviceId, "device ID");
        requireValue(language, "language");
        requireValue(securityPatchLevel, "security patch level");
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "が空です。");
        }
    }
}
