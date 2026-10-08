package com.travelplan.search.index;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the Elasticsearch index in step with travel-service.
 *
 * <ol>
 *   <li><b>Change notifications.</b> travel-service posts "travel X changed" after each commit. The ID
 *   is queued; every second the queue is drained: for each ID the current travel is fetched from
 *   travel-service and indexed if it is bookable, otherwise removed. Applying the same ID twice gives
 *   the same result, so duplicates and retries are safe (idempotent).</li>
 *   <li><b>Full rebuild.</b> On start-up and every few minutes, every bookable travel is copied into a
 *   brand-new versioned index and the {@code travels} alias is switched to it in one atomic step.
 *   This repairs anything a lost notification left behind (drift).</li>
 * </ol>
 * Both run on the single scheduler thread and are synchronized, so they never interleave.
 */
@Service
public class TravelIndexer {
    private static final Logger log = LoggerFactory.getLogger(TravelIndexer.class);
    private static final int MAX_CHANGES_PER_RUN = 500;

    private final TravelIndex index;
    private final TravelServiceClient travels;
    private final Clock clock;
    private final Duration rebuildInterval;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Failure> failures = new ConcurrentHashMap<>();
    private volatile Instant lastAppliedAt;
    private final AtomicReference<Rebuild> lastRebuild = new AtomicReference<>();

    public TravelIndexer(TravelIndex index, TravelServiceClient travels, Clock clock,
                         @Value("${search.sync.rebuild-interval:PT5M}") Duration rebuildInterval,
                         ObjectProvider<MeterRegistry> meters) {
        this.rebuildInterval = rebuildInterval;
        this.index = index;
        this.travels = travels;
        this.clock = clock;
        meters.ifAvailable(registry -> {
            Gauge.builder("search.index.pending", pending, Set::size)
                    .description("Travel changes waiting to be applied to the index").register(registry);
            Gauge.builder("search.index.failing", failures, Map::size)
                    .description("Travel changes whose last indexing attempt failed").register(registry);
        });
    }

    /** Records a change notification. Returns at once; the scheduler applies it. */
    public void travelChanged(UUID travelId) {
        pending.add(travelId);
    }

    @Scheduled(fixedDelayString = "${search.sync.apply-interval:PT1S}",
            initialDelayString = "${search.sync.apply-initial-delay:PT1S}")
    public synchronized void applyPendingChanges() {
        List<UUID> batch = new ArrayList<>(pending).subList(0, Math.min(pending.size(), MAX_CHANGES_PER_RUN));
        for (UUID travelId : batch) {
            try {
                apply(travelId);
                pending.remove(travelId);
                failures.remove(travelId);
                lastAppliedAt = clock.instant();
            } catch (RuntimeException ex) {
                // The ID stays queued and is retried on the next run; the failure is shown in the status view.
                failures.merge(travelId, new Failure(travelId, 1, reason(ex), clock.instant()),
                        (old, next) -> new Failure(travelId, old.attempts() + 1, next.lastError(), next.lastAttemptAt()));
                if (ex instanceof SearchUnavailable) return; // Elasticsearch is down; no point trying the rest now.
            }
        }
    }

    /** Makes the index match travel-service for one travel. */
    void apply(UUID travelId) {
        Optional<TravelDocument> current = travels.travel(travelId);
        if (current.isPresent() && current.get().searchable()) index.put(current.get().indexedAt(clock.instant()));
        else index.remove(travelId);
    }

    /**
     * Checked every 30 seconds: rebuilds when the last rebuild is older than the rebuild interval, and
     * retries soon after a failed one (for example when travel-service started after search-service).
     */
    @Scheduled(fixedDelayString = "${search.sync.rebuild-check-interval:PT30S}",
            initialDelayString = "${search.sync.rebuild-initial-delay:PT5S}")
    public void scheduledRebuild() {
        Rebuild last = lastRebuild.get();
        boolean due = last == null || !"SUCCEEDED".equals(last.outcome())
                || last.finishedAt().plus(rebuildInterval).isBefore(clock.instant());
        if (!due) return;
        try {
            rebuild();
        } catch (RuntimeException ex) {
            log.warn("Search index rebuild failed: {}", reason(ex));
        }
    }

    /**
     * Builds a fresh index from travel-service's bookable travels and switches the alias to it.
     * Searches keep using the old index until the switch, so there is no search downtime. Changes
     * notified meanwhile stay queued and are applied to the new index right after.
     */
    public synchronized Rebuild rebuild() {
        Instant started = clock.instant();
        String name = TravelIndex.INDEX_PREFIX + started.toEpochMilli();
        try {
            List<TravelDocument> documents = travels.bookableTravels().stream()
                    .filter(TravelDocument::searchable).map(document -> document.indexedAt(started)).toList();
            String previous = index.currentIndex();
            index.createIndex(name);
            try {
                index.bulkIndex(name, documents);
                index.refresh(name);
                index.switchAlias(previous, name);
            } catch (RuntimeException ex) {
                index.deleteIndex(name);
                throw ex;
            }
            index.deleteIndicesOlderThan(name);
            lastRebuild.set(new Rebuild(name, documents.size(), started, clock.instant(), "SUCCEEDED", null));
            lastAppliedAt = clock.instant();
            log.info("Search index rebuilt: {} with {} travels", name, documents.size());
            return lastRebuild.get();
        } catch (RuntimeException ex) {
            lastRebuild.set(new Rebuild(name, 0, started, clock.instant(), "FAILED", reason(ex)));
            throw ex;
        }
    }

    public Status status() {
        String current = null;
        long documents = 0;
        boolean available = true;
        try {
            current = index.currentIndex();
            documents = index.count();
        } catch (SearchUnavailable ex) {
            available = false;
        }
        return new Status(TravelIndex.ALIAS, current, TravelIndex.MAPPING_VERSION, available, documents,
                pending.size(), new ArrayList<>(failures.values()), lastAppliedAt, lastRebuild.get());
    }

    public Instant lastAppliedAt() {
        return lastAppliedAt;
    }

    private static String reason(RuntimeException ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    public record Failure(UUID travelId, int attempts, String lastError, Instant lastAttemptAt) {}

    public record Rebuild(String index, int documents, Instant startedAt, Instant finishedAt, String outcome,
                          String error) {}

    /** Operational view for admins: how far behind the index is and which changes keep failing. */
    public record Status(String alias, String currentIndex, String mappingVersion, boolean elasticsearchAvailable,
                         long documents, int pendingChanges, List<Failure> failingChanges, Instant lastAppliedAt,
                         Rebuild lastRebuild) {}
}
