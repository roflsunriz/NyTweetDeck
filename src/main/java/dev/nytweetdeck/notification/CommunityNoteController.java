package dev.nytweetdeck.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community-notes")
public class CommunityNoteController {

    private final CommunityNoteService service;

    public CommunityNoteController(CommunityNoteService service) {
        this.service = service;
    }

    @GetMapping("/{noteId}")
    CommunityNoteDetail detail(
            @PathVariable String noteId,
            @RequestParam String accountId,
            @RequestParam(defaultValue = "ja") String language) {
        return service.detail(accountId, noteId, language);
    }

    @GetMapping("/{noteId}/translation")
    CommunityNoteTranslation translation(@PathVariable String noteId,
            @RequestParam String accountId, @RequestParam String targetLanguage) {
        return service.translate(accountId, noteId, targetLanguage);
    }
}
