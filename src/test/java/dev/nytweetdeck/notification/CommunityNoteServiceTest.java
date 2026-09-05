package dev.nytweetdeck.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nytweetdeck.post.PostTranslationService;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CommunityNoteServiceTest {
    @Test
    void prefersBundledTranslationWithoutCallingLiveGeneration() {
        var mapper = JsonMapper.builder().build();
        var calls = new AtomicInteger();
        var graph = new AuthenticatedGraphQlClient(null, null, null, null) {
            @Override public GraphQlResult execute(String account, String purpose, Map<String, Object> variables, String language) {
                calls.incrementAndGet();
                return new GraphQlResult(purpose, "BirdwatchFetchOneNote", """
                        {"data":{"birdwatch_note_by_rest_id":{"rest_id":"555","grok_translated_community_note_with_availability":{"is_available":true,"data":{"translation":"同梱訳","destination_language":"ja","source_language":"en","rich_text_entities":[]}}}}}
                        """);
            }
        };
        var service = new CommunityNoteService(graph, new CommunityNoteResponseParser(mapper), null, null);
        assertThat(service.translate("account", "555", "ja").text()).isEqualTo("同梱訳");
        assertThat(service.translate("account", "555", "ja").text()).isEqualTo("同梱訳");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void fallsBackOnlyWhenUnprovidedAndReusesSuccessfulLiveResult() {
        var bundledCalls = new AtomicInteger();
        var liveCalls = new AtomicInteger();
        var mapper = JsonMapper.builder().build();
        var graph = new AuthenticatedGraphQlClient(null, null, null, null) {
            @Override public GraphQlResult execute(String account, String purpose, Map<String, Object> variables, String language) {
                bundledCalls.incrementAndGet();
                return new GraphQlResult(purpose, "BirdwatchFetchOneNote", """
                        {"data":{"birdwatch_note_by_rest_id":{"rest_id":"555","grok_translated_community_note_with_availability":{"is_available":false}}}}
                        """);
            }
        };
        var rest = new AuthenticatedRestClient(null, null, null) {
            @Override public RestResult postJson(String account, String endpoint, String body, String language) {
                liveCalls.incrementAndGet();
                assertThat(endpoint).isEqualTo("grokTranslation");
                var input = mapper.readTree(body);
                assertThat(input.path("content_type").asString()).isEqualTo("COMMUNITY_NOTE");
                assertThat(input.path("id").asString()).isEqualTo("555");
                assertThat(language).isEqualTo("ja");
                return new RestResult(endpoint, "{\"result\":{\"text\":\"ライブ訳\"}}");
            }
        };
        var service = new CommunityNoteService(graph, new CommunityNoteResponseParser(mapper), null, new PostTranslationService(rest, mapper));
        assertThat(service.translate("account", "555", "ja").text()).isEqualTo("ライブ訳");
        assertThat(service.translate("account", "555", "ja").text()).isEqualTo("ライブ訳");
        assertThat(bundledCalls.get()).isEqualTo(1);
        assertThat(liveCalls.get()).isEqualTo(1);
    }

    @Test
    void liveFailureIsNotCachedAsUnavailableOrSuccessful() {
        var mapper = JsonMapper.builder().build();
        var calls = new AtomicInteger();
        var rest = new AuthenticatedRestClient(null, null, null) {
            @Override public RestResult postJson(String account, String endpoint, String body, String language) {
                return new RestResult(endpoint, calls.incrementAndGet() == 1 ? "{\"error\":\"failed\"}" : "{\"result\":{\"text\":\"Recovered\"}}");
            }
        };
        var service = new PostTranslationService(rest, mapper);
        assertThatThrownBy(() -> service.translateCommunityNote("account", "555", "ja")).hasMessageContaining("解析できません");
        assertThat(service.translateCommunityNote("account", "555", "ja").text()).isEqualTo("Recovered");
        assertThat(calls.get()).isEqualTo(2);
    }
}
