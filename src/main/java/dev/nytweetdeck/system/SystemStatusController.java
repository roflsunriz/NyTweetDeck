package dev.nytweetdeck.system;

import dev.nytweetdeck.post.PostTranslationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final PostTranslationService translationService;

    public SystemStatusController(PostTranslationService translationService) {
        this.translationService = translationService;
    }

    @GetMapping("/status")
    public SystemStatus status() {
        return new SystemStatus("ready", 1);
    }

    @GetMapping("/translation-health")
    public PostTranslationService.TranslationHealth translationHealth() {
        return translationService.health();
    }

    public record SystemStatus(String status, int apiVersion) {}
}
