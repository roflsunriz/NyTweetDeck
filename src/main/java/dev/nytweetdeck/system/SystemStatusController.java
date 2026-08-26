package dev.nytweetdeck.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    @GetMapping("/status")
    public SystemStatus status() {
        return new SystemStatus("ready", 1);
    }

    public record SystemStatus(String status, int apiVersion) {}
}
