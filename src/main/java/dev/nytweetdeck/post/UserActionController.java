package dev.nytweetdeck.post;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserActionController {

    private final UserActionService userActionService;

    public UserActionController(UserActionService userActionService) {
        this.userActionService = userActionService;
    }

    @PostMapping("/{userId}/actions/{action}")
    public UserActionService.UserActionResult action(
            @PathVariable String userId,
            @PathVariable String action,
            @RequestParam String accountId) {
        return userActionService.execute(accountId, userId, action);
    }
}
