package dev.nytweetdeck.post;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts")
public class PostActionController {

    private final PostActionService actionService;

    public PostActionController(PostActionService actionService) {
        this.actionService = actionService;
    }

    @PostMapping("/{postId}/actions/{action}")
    public PostActionService.ActionResult action(
            @PathVariable String postId,
            @PathVariable String action,
            @RequestParam String accountId) {
        return actionService.execute(accountId, postId, action);
    }
}
