package dev.nytweetdeck.notification;

import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final NotificationResponseParser notificationParser;
    private final TimelineResponseParser timelineParser;

    public NotificationController(
            AuthenticatedGraphQlClient graphQlClient,
            NotificationResponseParser notificationParser,
            TimelineResponseParser timelineParser) {
        this.graphQlClient = graphQlClient;
        this.notificationParser = notificationParser;
        this.timelineParser = timelineParser;
    }

    @GetMapping
    public NotificationPage notifications(
            @RequestParam String accountId,
            @RequestParam(required = false) String cursor) {
        var variables = new LinkedHashMap<String, Object>();
        variables.put("timeline_type", "All");
        variables.put("count", 20);
        if (cursor != null && !cursor.isBlank()) {
            variables.put("cursor", cursor);
        }
        var result = graphQlClient.execute(accountId, "notifications", variables);
        var timeline = timelineParser.parse(result.rawJson());
        return new NotificationPage(
                notificationParser.parse(result.rawJson()),
                timeline.posts(),
                timeline.nextCursor());
    }
}
