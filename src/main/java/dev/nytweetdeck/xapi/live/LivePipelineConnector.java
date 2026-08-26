package dev.nytweetdeck.xapi.live;

import java.util.Set;
import java.util.function.Consumer;

public interface LivePipelineConnector {

    Connection open(
            String accountId,
            Set<String> topics,
            Consumer<String> eventConsumer,
            Consumer<Throwable> errorConsumer);

    interface Connection extends AutoCloseable {
        @Override
        void close();
    }
}
