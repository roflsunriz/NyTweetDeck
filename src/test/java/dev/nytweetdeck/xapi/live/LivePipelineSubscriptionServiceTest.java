package dev.nytweetdeck.xapi.live;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nytweetdeck.timeline.TimelineEventBus;
import dev.nytweetdeck.account.vault.VaultLockedEvent;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LivePipelineSubscriptionServiceTest {

    @Test
    void mergesColumnTopicsAndForwardsEventsToTheAccountBus() throws Exception {
        var connector = new FakeConnector();
        var eventBus = new TimelineEventBus();
        var received = new ArrayList<TimelineEventBus.TimelineEvent>();
        var eventSubscription = eventBus.subscribe("account-1", received::add);
        var service = new LivePipelineSubscriptionService(
                connector,
                new LivePipelineEventParser(JsonMapper.builder().build()),
                eventBus,
                null);

        service.update("account-1", "column-a", java.util.List.of("1"), false);
        service.update("account-1", "column-b", java.util.List.of("2"), false);

        assertThat(connector.opens).hasSize(2);
        assertThat(connector.opens.getLast().topics())
                .containsExactlyInAnyOrder("/tweet_engagement/1", "/tweet_engagement/2");
        assertThat(connector.opens.getFirst().closed()).isTrue();

        connector.opens.getLast().eventConsumer().accept("""
                {"topic":"/tweet_engagement/2","payload":{"tweet_engagement":{"favorite_count":"9"}}}
                """);
        assertThat(received).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo("live:tweet_engagement");
            assertThat(event.postId()).isEqualTo("2");
        });

        service.remove("account-1", "column-a");
        assertThat(connector.opens.getLast().topics()).containsExactly("/tweet_engagement/2");
        var activeConnection = connector.opens.getLast();
        service.closeAll(new VaultLockedEvent());
        assertThat(activeConnection.closed()).isTrue();
        eventSubscription.close();
    }

    private static final class FakeConnector implements LivePipelineConnector {
        private final ArrayList<FakeConnection> opens = new ArrayList<>();

        @Override
        public Connection open(
                String accountId,
                Set<String> topics,
                Consumer<String> eventConsumer,
                Consumer<Throwable> errorConsumer) {
            var connection = new FakeConnection(topics, eventConsumer);
            opens.add(connection);
            return connection;
        }
    }

    private static final class FakeConnection implements LivePipelineConnector.Connection {
        private final Set<String> topics;
        private final Consumer<String> eventConsumer;
        private boolean closed;

        private FakeConnection(Set<String> topics, Consumer<String> eventConsumer) {
            this.topics = Set.copyOf(topics);
            this.eventConsumer = eventConsumer;
        }

        @Override
        public void close() {
            closed = true;
        }

        private Set<String> topics() {
            return topics;
        }

        private Consumer<String> eventConsumer() {
            return eventConsumer;
        }

        private boolean closed() {
            return closed;
        }
    }
}
