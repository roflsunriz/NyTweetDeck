package dev.nytweetdeck.xapi.credentials;

import dev.nytweetdeck.xapi.profile.AndroidApiProfileService;
import dev.nytweetdeck.xapi.device.AndroidDeviceProfileStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/x-api")
public class XApiReadinessController {

    private final AndroidApiProfileService profileService;
    private final AndroidClientCredentialsProvider credentialsProvider;
    private final AndroidDeviceProfileStore deviceProfileStore;

    public XApiReadinessController(
            AndroidApiProfileService profileService,
            AndroidClientCredentialsProvider credentialsProvider,
            AndroidDeviceProfileStore deviceProfileStore) {
        this.profileService = profileService;
        this.credentialsProvider = credentialsProvider;
        this.deviceProfileStore = deviceProfileStore;
    }

    @GetMapping("/readiness")
    public Readiness readiness() {
        return new Readiness(
                profileService.profile().versionName(),
                credentialsProvider.find().isPresent(),
                deviceProfileStore.find().isPresent());
    }

    public record Readiness(
            String androidApiVersion,
            boolean clientCredentialsAvailable,
            boolean deviceProfileAvailable) {}
}
