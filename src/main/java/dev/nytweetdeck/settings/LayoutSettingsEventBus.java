package dev.nytweetdeck.settings;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class LayoutSettingsEventBus {

    private final CopyOnWriteArrayList<Consumer<LayoutSettingsEvent>> listeners =
            new CopyOnWriteArrayList<>();

    public AutoCloseable subscribe(Consumer<LayoutSettingsEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void publish(long revision) {
        var event = new LayoutSettingsEvent(UUID.randomUUID().toString(), revision);
        for (var listener : listeners) {
            listener.accept(event);
        }
    }

    public record LayoutSettingsEvent(String id, long revision) {}
}
