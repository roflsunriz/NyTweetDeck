package dev.nytweetdeck.xapi.device;

import dev.nytweetdeck.xapi.http.AndroidDeviceIdentity;
import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import java.util.Locale;
import java.util.UUID;

public record AndroidDeviceProfile(
        int schemaVersion,
        String model,
        String osVersion,
        String manufacturer,
        String brand,
        String product,
        String securityPatchLevel,
        String language,
        String clientUuid,
        String deviceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AndroidDeviceProfile {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("未対応の端末プロファイル版です: " + schemaVersion);
        }
        validateHeaderValue(model, "model");
        validateHeaderValue(osVersion, "osVersion");
        validateHeaderValue(manufacturer, "manufacturer");
        validateHeaderValue(brand, "brand");
        validateHeaderValue(product, "product");
        validateHeaderValue(securityPatchLevel, "securityPatchLevel");
        validateHeaderValue(language, "language");
        validateIdentifier(clientUuid, "clientUuid");
        validateIdentifier(deviceId, "deviceId");
    }

    public static AndroidDeviceProfile create(
            String model,
            String osVersion,
            String manufacturer,
            String brand,
            String product,
            String securityPatchLevel,
            String language) {
        var clientUuid = UUID.randomUUID().toString();
        var deviceId = UUID.randomUUID().toString().replace("-", "");
        return new AndroidDeviceProfile(
                CURRENT_SCHEMA_VERSION,
                model,
                osVersion,
                manufacturer,
                brand,
                product,
                securityPatchLevel,
                language.toLowerCase(Locale.ROOT),
                clientUuid,
                deviceId);
    }

    public AndroidDeviceIdentity toIdentity(AndroidApiProfile apiProfile) {
        var userAgent = "TwitterAndroid/"
                + apiProfile.versionName()
                + " ("
                + apiProfile.versionCode()
                + "-r-0) "
                + model
                + "/"
                + osVersion
                + " ("
                + manufacturer
                + ";"
                + model
                + ";"
                + brand
                + ";"
                + product
                + ";0;;0;)";
        return new AndroidDeviceIdentity(
                userAgent, clientUuid, deviceId, language, securityPatchLevel);
    }

    private static void validateHeaderValue(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(name + "が空、または長すぎます。");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + "に改行は使用できません。");
        }
    }

    private static void validateIdentifier(String value, String name) {
        validateHeaderValue(value, name);
        if (!value.matches("[A-Za-z0-9-]{16,64}")) {
            throw new IllegalArgumentException(name + "の形式が不正です。");
        }
    }
}
