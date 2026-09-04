package dev.nytweetdeck.update;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/updates")
public class UpdateController {

    private final DesktopReleaseService releaseService;

    public UpdateController(DesktopReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping("/desktop/latest")
    public DesktopReleaseService.DesktopRelease latestDesktopRelease() {
        return releaseService.latestStableRelease();
    }
}
