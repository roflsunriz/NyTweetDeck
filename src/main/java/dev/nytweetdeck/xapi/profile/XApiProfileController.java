package dev.nytweetdeck.xapi.profile;

import java.net.URI;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/x-api")
public class XApiProfileController {

    private final AndroidApiProfileService profileService;

    public XApiProfileController(AndroidApiProfileService profileService) {
        this.profileService = profileService;
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
            Map<String, AndroidApiProfile.GraphQlOperation> graphqlOperations) {}
}
