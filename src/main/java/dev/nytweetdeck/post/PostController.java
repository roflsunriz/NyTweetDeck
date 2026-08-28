package dev.nytweetdeck.post;

import dev.nytweetdeck.timeline.TimelinePage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    private final PostService postService;
    private final PostTranslationService translationService;

    public PostController(PostService postService, PostTranslationService translationService) {
        this.postService = postService;
        this.translationService = translationService;
    }

    @GetMapping("/{postId}/translation")
    public PostTranslationService.TranslationResult translation(
            @PathVariable String postId,
            @RequestParam String accountId,
            @RequestParam String sourceLanguage,
            @RequestParam String targetLanguage) {
        return translationService.translate(
                accountId, postId, sourceLanguage, targetLanguage);
    }

    @GetMapping("/{postId}")
    public PostService.PostDetail detail(
            @PathVariable String postId,
            @RequestParam String accountId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "ja") String language,
            @RequestParam(defaultValue = "relevance") String replySort) {
        return postService.detail(accountId, postId, cursor, language, replySort);
    }

    @PostMapping
    public TimelinePage.Post create(@Valid @RequestBody CreatePostRequest request) {
        return postService.create(
                request.accountId(), request.text(), request.inReplyToPostId(), request.quotePostId());
    }

    public record CreatePostRequest(
            @NotBlank String accountId,
            @NotBlank @Size(max = 4000) String text,
            String inReplyToPostId,
            String quotePostId) {}
}
