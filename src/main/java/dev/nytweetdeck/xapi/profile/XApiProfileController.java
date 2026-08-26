package dev.nytweetdeck.xapi.profile;

import java.net.URI;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/x-api")
public class XApiProfileController {

    private final XApiProfileService profileService;
    private final XApiMetadataRefreshService refreshService;

    public XApiProfileController(
            XApiProfileService profileService, XApiMetadataRefreshService refreshService) {
        this.profileService = profileService;
        this.refreshService = refreshService;
    }

    @GetMapping("/refresh/status")
    public XApiMetadataRefreshService.RefreshStatus refreshStatus() {
        return refreshService.status();
    }

    @PostMapping("/refresh")
    public XApiMetadataRefreshService.RefreshStatus refresh() {
        return refreshService.refreshNow();
    }

    @GetMapping("/profile")
    public PublicProfile profile() {
        var profile = profileService.profile();
        return new PublicProfile(
                profile.packageName(),
                profile.versionName(),
                profile.versionCode(),
                profile.restBaseUri(),
                profile.graphqlBaseUri(),
                profile.standardHeaders(),
                profile.restEndpoints(),
                profile.graphqlOperations());
    }

    public record PublicProfile(
            String packageName,
            String versionName,
            long versionCode,
            URI restBaseUri,
            URI graphqlBaseUri,
            Map<String, String> standardHeaders,
            Map<String, String> restEndpoints,
            Map<String, XApiProfile.GraphQlOperation> graphqlOperations) {}
}
