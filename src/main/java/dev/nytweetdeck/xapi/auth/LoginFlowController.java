package dev.nytweetdeck.xapi.auth;

import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/login")
public class LoginFlowController {

    private final LoginFlowService loginFlowService;

    public LoginFlowController(LoginFlowService loginFlowService) {
        this.loginFlowService = loginFlowService;
    }

    @PostMapping("/start")
    public LoginFlowService.LoginProgress start() {
        return loginFlowService.start();
    }

    @PostMapping("/{sessionId}/submit")
    public LoginFlowService.LoginProgress submit(
            @PathVariable String sessionId, @RequestBody LoginSubmission request) {
        try {
            return loginFlowService.submit(
                    sessionId,
                    request.subtaskId(),
                    request.value(),
                    request.choiceIds(),
                    request.link());
        } finally {
            if (request.value() != null) {
                Arrays.fill(request.value(), '\0');
            }
        }
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String sessionId) {
        loginFlowService.cancel(sessionId);
    }

    public record LoginSubmission(
            String subtaskId, char[] value, List<String> choiceIds, String link) {}
}
