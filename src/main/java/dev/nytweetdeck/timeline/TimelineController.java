package dev.nytweetdeck.timeline;

import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
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

    public TimelineController(
            AuthenticatedGraphQlClient graphQlClient,
            TimelineQueryFactory queryFactory,
            TimelineResponseParser responseParser) {
        this.graphQlClient = graphQlClient;
        this.queryFactory = queryFactory;
        this.responseParser = responseParser;
    }

    @GetMapping("/{kind}")
    public TimelinePage timeline(
            @PathVariable String kind,
            @RequestParam String accountId,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "ja") String language,
            @RequestParam(defaultValue = "latest") String sort) {
        var query = queryFactory.create(kind, target, cursor, sort);
        var result = graphQlClient.execute(
                accountId, query.purpose(), query.variables(), language);
        return responseParser.parse(result.rawJson(), sort);
    }
}
