package dev.nytweetdeck.xapi.http;

import dev.nytweetdeck.xapi.profile.AndroidApiProfile;
import java.net.http.HttpRequest;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AndroidRequestHeaders {

    public void apply(
            HttpRequest.Builder builder,
            AndroidApiProfile profile,
            AndroidDeviceIdentity identity) {
        for (Map.Entry<String, String> header : profile.standardHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        builder.header("User-Agent", identity.userAgent());
        builder.header("X-Client-UUID", identity.clientUuid());
        builder.header("Accept-Language", identity.language());
        builder.header("X-Twitter-Client-Language", identity.language());
        builder.header("X-Twitter-Client-DeviceID", identity.deviceId());
        builder.header("OS-Security-Patch-Level", identity.securityPatchLevel());
    }
}
