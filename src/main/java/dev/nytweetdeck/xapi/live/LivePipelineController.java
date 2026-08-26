package dev.nytweetdeck.xapi.live;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/live/subscriptions")
public class LivePipelineController {

    private final LivePipelineSubscriptionService subscriptionService;

    public LivePipelineController(LivePipelineSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PutMapping("/{subscriberId}")
    public LivePipelineSubscriptionService.SubscriptionStatus update(
            @PathVariable String subscriberId, @RequestBody SubscriptionRequest request) {
        return subscriptionService.update(
                request.accountId(), subscriberId, request.postIds(), request.directMessages());
    }

    @DeleteMapping("/{subscriberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable String subscriberId, @RequestParam String accountId) {
        subscriptionService.remove(accountId, subscriberId);
    }

    public record SubscriptionRequest(
            String accountId, List<String> postIds, boolean directMessages) {
        public SubscriptionRequest {
            postIds = postIds == null ? List.of() : List.copyOf(postIds);
        }
    }
}
