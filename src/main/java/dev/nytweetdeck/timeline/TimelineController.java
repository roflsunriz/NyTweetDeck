package dev.nytweetdeck.timeline;

import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/timelines")
public class TimelineController {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final TimelineQueryFactory queryFactory;
    private final TimelineResponseParser responseParser;
    private final AuthenticatedRestClient restClient;

    public TimelineController(
            AuthenticatedGraphQlClient graphQlClient,
            TimelineQueryFactory queryFactory,
            TimelineResponseParser responseParser,
            AuthenticatedRestClient restClient) {
        this.graphQlClient = graphQlClient;
        this.queryFactory = queryFactory;
        this.responseParser = responseParser;
        this.restClient = restClient;
    }

    @GetMapping("/{kind}")
    public TimelinePage timeline(
            @PathVariable String kind,
            @RequestParam String accountId,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String cursor) {
        var query = queryFactory.create(kind, target, cursor);
        if (query.purpose().equals("notifications")) {
            var parameters = new LinkedHashMap<String, String>();
            parameters.put("count", "20");
            if (cursor != null && !cursor.isBlank()) {
                parameters.put("cursor", cursor);
            }
            var result = restClient.get(accountId, "notificationsAll", parameters);
            return responseParser.parse(result.rawJson());
        }
        var result = graphQlClient.execute(accountId, query.purpose(), query.variables());
        return responseParser.parse(result.rawJson());
    }
}
