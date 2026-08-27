package dev.nytweetdeck.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserDirectoryController {

    private final UserDirectoryService service;
    private final UserProfileService profileService;

    public UserDirectoryController(
            UserDirectoryService service, UserProfileService profileService) {
        this.service = service;
        this.profileService = profileService;
    }

    @GetMapping("/resolve")
    public UserDirectoryService.UserOption resolve(
            @RequestParam String accountId, @RequestParam String username) {
        return service.resolve(accountId, username);
    }

    @GetMapping("/{userId}")
    public UserProfilePage profile(
            @org.springframework.web.bind.annotation.PathVariable String userId,
            @RequestParam String accountId,
            @RequestParam(defaultValue = "ja") String language) {
        return profileService.profile(accountId, userId, language);
    }

    @GetMapping("/{userId}/timeline")
    public dev.nytweetdeck.timeline.TimelinePage timeline(
            @org.springframework.web.bind.annotation.PathVariable String userId,
            @RequestParam String accountId,
            @RequestParam(defaultValue = "all") String tab,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "ja") String language) {
        return profileService.timeline(accountId, userId, tab, cursor, language);
    }
}
