package dev.nytweetdeck.list;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ListDirectoryServiceTest {

    @Test
    void usesCurrentListSearchTimelineVariables() {
        var purpose = new AtomicReference<String>();
        var variables = new AtomicReference<Map<String, Object>>();
        var client = new AuthenticatedGraphQlClient(null, null, null, null) {
            @Override
            public GraphQlResult execute(
                    String accountId, String requestPurpose, Map<String, Object> requestVariables) {
                purpose.set(requestPurpose);
                variables.set(Map.copyOf(requestVariables));
                return new GraphQlResult(requestPurpose, "ListSearchTimeline", "{\"data\":{}}");
            }
        };
        var service = new ListDirectoryService(
                client,
                null,
                new ListDirectoryParser(JsonMapper.builder().build()));

        service.list("account-1", "search", "  OpenAI  ", null);

        assertThat(purpose.get()).isEqualTo("listSearch");
        assertThat(variables.get())
                .containsEntry("rawQuery", "OpenAI")
                .containsEntry("count", 20)
                .doesNotContainKey("querySource");
    }
}
