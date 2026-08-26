package dev.nytweetdeck.trend;

import dev.nytweetdeck.timeline.TimelineQueryFactory;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trends")
public class TrendController {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final TimelineQueryFactory queryFactory;
    private final TrendResponseParser responseParser;

    public TrendController(
            AuthenticatedGraphQlClient graphQlClient,
            TimelineQueryFactory queryFactory,
            TrendResponseParser responseParser) {
        this.graphQlClient = graphQlClient;
        this.queryFactory = queryFactory;
        this.responseParser = responseParser;
    }

    @GetMapping
    public TrendPage trends(
            @RequestParam String accountId,
            @RequestParam(required = false) String cursor) {
        var query = queryFactory.create("trends", null, cursor);
        var result = graphQlClient.execute(accountId, query.purpose(), query.variables());
        return responseParser.parse(result.rawJson());
    }
}
