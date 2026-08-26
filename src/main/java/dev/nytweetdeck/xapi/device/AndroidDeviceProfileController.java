package dev.nytweetdeck.xapi.device;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/x-api/device-profile")
public class AndroidDeviceProfileController {

    private final AndroidDeviceProfileStore store;

    public AndroidDeviceProfileController(AndroidDeviceProfileStore store) {
        this.store = store;
    }

    @GetMapping
    public Optional<AndroidDeviceProfile> get() {
        return store.find();
    }

    @PutMapping
    public AndroidDeviceProfile put(@Valid @RequestBody DeviceProfileRequest request) {
        var existing = store.find();
        var profile = existing
                .map(value -> new AndroidDeviceProfile(
                        AndroidDeviceProfile.CURRENT_SCHEMA_VERSION,
                        request.model(),
                        request.osVersion(),
                        request.manufacturer(),
                        request.brand(),
                        request.product(),
                        request.securityPatchLevel(),
                        request.language(),
                        value.clientUuid(),
                        value.deviceId()))
                .orElseGet(() -> AndroidDeviceProfile.create(
                        request.model(),
                        request.osVersion(),
                        request.manufacturer(),
                        request.brand(),
                        request.product(),
                        request.securityPatchLevel(),
                        request.language()));
        store.save(profile);
        return profile;
    }

    public record DeviceProfileRequest(
            @NotBlank @Size(max = 100) String model,
            @NotBlank @Size(max = 30) String osVersion,
            @NotBlank @Size(max = 100) String manufacturer,
            @NotBlank @Size(max = 100) String brand,
            @NotBlank @Size(max = 100) String product,
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String securityPatchLevel,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?") String language) {}
}
