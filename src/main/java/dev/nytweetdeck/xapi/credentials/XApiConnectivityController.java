package dev.nytweetdeck.xapi.credentials;

import dev.nytweetdeck.xapi.auth.GuestAuthenticationClient;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfileStore;
import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/x-api/connectivity")
public class XApiConnectivityController {

    private final GuestAuthenticationClient guestAuthenticationClient;
    private final AndroidApiProfileService profileService;
    private final AndroidDeviceProfileStore deviceProfileStore;

    public XApiConnectivityController(
            GuestAuthenticationClient guestAuthenticationClient,
            AndroidApiProfileService profileService,
            AndroidDeviceProfileStore deviceProfileStore) {
        this.guestAuthenticationClient = guestAuthenticationClient;
        this.profileService = profileService;
        this.deviceProfileStore = deviceProfileStore;
    }

    @PostMapping("/guest")
    public ConnectivityResult verifyGuestAuthentication() {
        var profile = profileService.profile();
        var identity = deviceProfileStore.require().toIdentity(profile);
        var session = guestAuthenticationClient.activate(identity);
        return new ConnectivityResult(
                !session.bearerToken().isBlank(),
                !session.guestToken().isBlank(),
                profile.versionName(),
                Instant.now());
    }

    public record ConnectivityResult(
            boolean bearerTokenReceived,
            boolean guestTokenReceived,
            String androidApiVersion,
            Instant verifiedAt) {}
}
