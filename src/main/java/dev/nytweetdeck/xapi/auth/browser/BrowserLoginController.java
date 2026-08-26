package dev.nytweetdeck.xapi.auth.browser;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/login/browser")
public class BrowserLoginController {

    private final BrowserLoginService service;

    public BrowserLoginController(BrowserLoginService service) {
        this.service = service;
    }

    @PostMapping("/start")
    BrowserLoginService.BrowserLoginStatus start(
            @RequestParam String requestId, @RequestParam(defaultValue = "0") int attempt) {
        return service.start(requestId + ":" + attempt);
    }

    @GetMapping("/{sessionId}")
    BrowserLoginService.BrowserLoginStatus status(@PathVariable String sessionId) {
        return service.status(sessionId);
    }

    @PostMapping("/{sessionId}/capture")
    BrowserLoginService.BrowserLoginStatus capture(@PathVariable String sessionId) {
        return service.capture(sessionId);
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable String sessionId) {
        service.cancel(sessionId);
    }
}
