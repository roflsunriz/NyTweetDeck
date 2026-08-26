package dev.nytweetdeck.notification;

import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final AuthenticatedRestClient restClient;
    private final NotificationResponseParser notificationParser;
    private final TimelineResponseParser timelineParser;

    public NotificationController(
            AuthenticatedRestClient restClient,
            NotificationResponseParser notificationParser,
            TimelineResponseParser timelineParser) {
        this.restClient = restClient;
        this.notificationParser = notificationParser;
        this.timelineParser = timelineParser;
    }

    @GetMapping
    public NotificationPage notifications(
            @RequestParam String accountId,
            @RequestParam(required = false) String cursor) {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("count", "20");
        if (cursor != null && !cursor.isBlank()) {
            parameters.put("cursor", cursor);
        }
        var result = restClient.get(accountId, "notificationsAll", parameters);
        var timeline = timelineParser.parse(result.rawJson());
        return new NotificationPage(
                notificationParser.parse(result.rawJson()),
                timeline.posts(),
                timeline.nextCursor());
    }
}
