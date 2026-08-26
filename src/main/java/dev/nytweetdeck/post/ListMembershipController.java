package dev.nytweetdeck.post;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class ListMembershipController {

    private final ListMembershipService membershipService;

    public ListMembershipController(ListMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/{userId}/lists/{listId}/{action}")
    public ListMembershipService.MembershipResult update(
            @PathVariable String userId,
            @PathVariable String listId,
            @PathVariable String action,
            @RequestParam String accountId) {
        return membershipService.execute(accountId, userId, listId, action);
    }
}
