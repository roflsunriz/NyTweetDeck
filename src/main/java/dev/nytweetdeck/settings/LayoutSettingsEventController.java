package dev.nytweetdeck.settings;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/settings")
public class LayoutSettingsEventController {

    private static final long EMITTER_TIMEOUT_MILLISECONDS = 120_000L;

    private final LayoutSettingsEventBus eventBus;

    public LayoutSettingsEventController(LayoutSettingsEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() throws IOException {
        var emitter = new SseEmitter(EMITTER_TIMEOUT_MILLISECONDS);
        var subscription = new AtomicReference<AutoCloseable>();
        subscription.set(eventBus.subscribe(event -> send(emitter, event, subscription)));
        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> close(subscription));
        emitter.onError(ignored -> close(subscription));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ready"));
        } catch (IOException exception) {
            close(subscription);
            throw exception;
        }
        return emitter;
    }

    private static void send(
            SseEmitter emitter,
            LayoutSettingsEventBus.LayoutSettingsEvent event,
            AtomicReference<AutoCloseable> subscription) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.id())
                    .name("layout-settings-update")
                    .data(event));
        } catch (IOException | IllegalStateException exception) {
            close(subscription);
            try {
                emitter.completeWithError(exception);
            } catch (IllegalStateException ignored) {
                // The servlet container may already have completed a disconnected request.
            }
        }
    }

    private static void close(AtomicReference<AutoCloseable> subscription) {
        var closeable = subscription.getAndSet(null);
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing an in-memory listener does not require recovery.
        }
    }
}
