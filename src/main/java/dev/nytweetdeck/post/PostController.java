package dev.nytweetdeck.post;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    private final PostService postService;
    private final PostTranslationService translationService;
    private final ComposerWebClient composerWebClient;

    public PostController(PostService postService, PostTranslationService translationService,
            ComposerWebClient composerWebClient) {
        this.postService = postService;
        this.translationService = translationService;
        this.composerWebClient = composerWebClient;
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
    public Object create(@Valid @RequestBody CreatePostRequest request) {
        if (request.mediaIds() == null && request.poll() == null && request.scheduledAt() == null &&
                request.place() == null && !request.paidPartnership() && !request.aiGenerated()) {
            return postService.create(request.accountId(), request.text(),
                    request.inReplyToPostId(), request.quotePostId());
        }
        return postService.createAdvanced(request.accountId(), new PostComposition(
                request.text(), request.inReplyToPostId(), request.quotePostId(), request.mediaIds(),
                request.poll(), request.scheduledAt(), request.place(),
                request.paidPartnership(), request.aiGenerated()));
    }

    @PostMapping(path = "/media", consumes = "multipart/form-data")
    public ComposerWebClient.MediaUpload uploadMedia(@RequestParam String accountId,
            @RequestParam("file") MultipartFile file) {
        return composerWebClient.upload(accountId, file);
    }

    @GetMapping("/places")
    public List<ComposerWebClient.Place> searchPlaces(@RequestParam String accountId,
            @RequestParam String query) {
        return composerWebClient.searchPlaces(accountId, query);
    }

    public record CreatePostRequest(
            @NotBlank String accountId,
            @Size(max = 4000) String text,
            String inReplyToPostId,
            String quotePostId,
            List<String> mediaIds,
            PostComposition.Poll poll,
            Instant scheduledAt,
            PostComposition.Place place,
            boolean paidPartnership,
            boolean aiGenerated) {}
}
