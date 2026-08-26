package dev.nytweetdeck.xapi.profile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class XApiMetadataRefreshService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(XApiMetadataRefreshService.class);

    private final XApiProfileService profileService;
    private final XWebMetadataResolver resolver;
    private final boolean autoRefresh;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform()
                    .daemon(true)
                    .name("x-web-metadata-refresh")
                    .unstarted(runnable));
    private final AtomicReference<RefreshStatus> status = new AtomicReference<>(
            new RefreshStatus(false, false, null, null, null, 0, null));

    public XApiMetadataRefreshService(
            XApiProfileService profileService,
            XWebMetadataResolver resolver,
            @Value("${nytweetdeck.x-api.auto-refresh:true}") boolean autoRefresh) {
        this.profileService = profileService;
        this.resolver = resolver;
        this.autoRefresh = autoRefresh;
    }

    @PostConstruct
    void schedule() {
        if (autoRefresh) {
            scheduler.schedule(this::refreshSafely, 5, TimeUnit.SECONDS);
            scheduler.scheduleWithFixedDelay(
                    this::refreshSafely, 6, 6, TimeUnit.HOURS);
        }
    }

    public synchronized RefreshStatus refreshNow() {
        var previous = status.get();
        var attempt = Instant.now();
        status.set(new RefreshStatus(
                true,
                previous.successful(),
                attempt,
                previous.lastSuccessfulAt(),
                previous.sourceVersion(),
                previous.updatedOperations(),
                null));
        try {
            var required = new LinkedHashSet<String>();
            profileService.profile().graphqlOperations().values().stream()
                    .map(XApiProfile.GraphQlOperation::operationName)
                    .forEach(required::add);
            var metadata = resolver.resolve(required);
            var updated = profileService.applyResolved(metadata);
            var completed = Instant.now();
            var result = new RefreshStatus(
                    false,
                    true,
                    attempt,
                    completed,
                    metadata.sourceVersion(),
                    updated,
                    null);
            status.set(result);
            LOGGER.info(
                    "X Web API metadataを更新しました: sourceVersion={}, operations={}",
                    metadata.sourceVersion(),
                    updated);
            return result;
        } catch (RuntimeException exception) {
            var result = new RefreshStatus(
                    false,
                    false,
                    attempt,
                    previous.lastSuccessfulAt(),
                    previous.sourceVersion(),
                    previous.updatedOperations(),
                    "REFRESH_FAILED");
            status.set(result);
            LOGGER.warn(
                    "X Web API metadata更新に失敗し、直前の検証済み定義を維持しました: cause={}",
                    exception.getClass().getSimpleName());
            return result;
        }
    }

    public RefreshStatus status() {
        return status.get();
    }

    private void refreshSafely() {
        refreshNow();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public record RefreshStatus(
            boolean refreshing,
            boolean successful,
            Instant lastAttemptAt,
            Instant lastSuccessfulAt,
            String sourceVersion,
            int updatedOperations,
            String errorCode) {}
}
