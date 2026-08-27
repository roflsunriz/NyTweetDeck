package dev.nytweetdeck.settings;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/layout")
public class LayoutSettingsController {

    private final LayoutSettingsStore store;
    private final LayoutSettingsEventBus eventBus;

    public LayoutSettingsController(LayoutSettingsStore store, LayoutSettingsEventBus eventBus) {
        this.store = store;
        this.eventBus = eventBus;
    }

    @GetMapping
    public ResponseEntity<LayoutSettingsStore.Snapshot> get() {
        return store.current()
                .map(snapshot -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(snapshot))
                .orElseGet(() -> ResponseEntity.noContent()
                        .cacheControl(CacheControl.noStore())
                        .build());
    }

    @PutMapping
    public ResponseEntity<LayoutSettingsStore.Snapshot> save(
            @RequestBody SaveLayoutRequest request) {
        if (request == null || request.expectedRevision() == null) {
            throw new IllegalArgumentException("設定保存要求が不正です。");
        }
        var result = store.save(request.expectedRevision(), request.layout());
        if (result.conflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .body(result.snapshot());
        }
        if (result.changed()) {
            eventBus.publish(result.snapshot().revision());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result.snapshot());
    }

    public record SaveLayoutRequest(Long expectedRevision, LayoutSettings layout) {}
}
