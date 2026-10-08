package com.travelplan.travel.recommend;

import com.travelplan.common.RequestIds;
import com.travelplan.travel.recommend.RecommendationEngine.Recommendations;
import com.travelplan.travel.recommend.RecommendationGraph.Drift;
import com.travelplan.travel.recommend.RecommendationGraph.HistoryRow;
import com.travelplan.travel.recommend.RecommendationGraph.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.neo4j.driver.exceptions.Neo4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Recommendations, event intake and reconciliation. Without Neo4j (local runs without the infra
 * profile, or an outage) every operation fails with {@link Unavailable}; events then stay in
 * engagement-service's outbox and are delivered again later.
 */
@Service
public class RecommendationService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final Set<String> SUBSCRIPTION_EVENTS = Set.of("SubscriptionReserved", "SubscriptionConfirmed",
            "SubscriptionPaymentFailed", "SubscriptionCancelled", "SubscriptionCompleted");

    private final RecommendationGraph graph;
    private final RecommendationEngine engine = new RecommendationEngine();
    private final Clock clock;
    private final AtomicReference<ReconcileStatus> lastReconcile = new AtomicReference<>(ReconcileStatus.NEVER);

    @Autowired
    public RecommendationService(ObjectProvider<RecommendationGraph> graphs) {
        this(graphs.getIfAvailable(), Clock.systemUTC());
    }

    RecommendationService(RecommendationGraph graph, Clock clock) {
        this.graph = graph;
        this.clock = clock;
    }

    public Recommendations recommend(UUID userId, int limit) {
        return withGraph(() -> engine.recommend(graph.history(userId),
                graph.candidates(userId, LocalDate.now(clock.withZone(ZoneOffset.UTC))), limit));
    }

    /**
     * Applies a batch of engagement-service events in order; unknown event types are skipped. An event
     * that cannot be applied (missing or malformed fields) is reported back as rejected so the sender
     * can park it, and the rest of the batch still applies. A graph outage fails the whole batch.
     */
    public EventResult apply(List<Event> events) {
        return withGraph(() -> {
            int applied = 0, duplicates = 0, stale = 0, ignored = 0;
            List<Rejected> rejected = new java.util.ArrayList<>();
            for (Event event : events) {
                Outcome[] result = new Outcome[1];
                try {
                    // Logged under the ID of the request that caused the event, not the relay's.
                    RequestIds.within(event.traceId(), () -> result[0] = apply(event));
                } catch (Neo4jException outage) {
                    throw outage;
                } catch (RuntimeException unprocessable) {
                    RequestIds.within(event.traceId(), () -> log.warn("Rejected event {} ({}): {}", event.eventId(),
                            event.eventType(), unprocessable.toString()));
                    rejected.add(new Rejected(event.eventId(), unprocessable.getClass().getSimpleName()
                            + (unprocessable.getMessage() == null ? "" : ": " + unprocessable.getMessage())));
                    continue;
                }
                Outcome outcome = result[0];
                if (outcome == null) ignored++;
                else switch (outcome) {
                    case APPLIED -> applied++;
                    case DUPLICATE -> duplicates++;
                    case STALE -> stale++;
                }
            }
            return new EventResult(applied, duplicates, stale, ignored, rejected);
        });
    }

    private Outcome apply(Event event) {
        Map<String, Object> payload = event.payload();
        if (SUBSCRIPTION_EVENTS.contains(event.eventType())) {
            return graph.applySubscription(event.eventId(), event.aggregateId(), uuid(payload, "travelerId"),
                    uuid(payload, "travelId"), String.valueOf(payload.get("status")), event.aggregateVersion());
        }
        if ("FeedbackSubmitted".equals(event.eventType())) {
            return graph.applyFeedback(event.eventId(), event.aggregateId(), uuid(payload, "travelerId"),
                    uuid(payload, "travelId"), ((Number) payload.get("rating")).intValue());
        }
        return null;
    }

    public void forgetUsers(Set<UUID> userIds) {
        if (graph != null && !userIds.isEmpty()) graph.forgetUsers(userIds);
    }

    /** Replaces the graph's history with engagement-service's snapshot and records what had drifted. */
    public ReconcileStatus reconcile(Supplier<List<HistoryRow>> snapshot) {
        ReconcileStatus status;
        try {
            Drift drift = withGraph(() -> graph.reconcile(snapshot.get()));
            status = new ReconcileStatus(clock.instant(), drift, null);
            if (drift.total() > 0) log.warn("Recommendation history drift repaired: {}", drift);
        } catch (RuntimeException failure) {
            status = new ReconcileStatus(clock.instant(), null, failure.getClass().getSimpleName());
            log.warn("Recommendation history reconciliation failed: {}", failure.toString());
        }
        lastReconcile.set(status);
        return status;
    }

    public Status status() {
        RecommendationGraph.Counts counts = null;
        if (graph != null) {
            try { counts = graph.counts(); } catch (Neo4jException unavailable) { /* reported as unavailable */ }
        }
        return new Status(counts != null, counts, lastReconcile.get());
    }

    private <T> T withGraph(Supplier<T> work) {
        if (graph == null) throw new Unavailable();
        try {
            return work.get();
        } catch (Neo4jException failure) {
            throw new Unavailable();
        }
    }

    private static UUID uuid(Map<String, Object> payload, String key) {
        return UUID.fromString(String.valueOf(payload.get(key)));
    }

    /** One engagement-service outbox event. */
    public record Event(UUID eventId, String eventType, UUID aggregateId, long aggregateVersion, Map<String, Object> payload,
                        String traceId) {
        public Event(UUID eventId, String eventType, UUID aggregateId, long aggregateVersion, Map<String, Object> payload) {
            this(eventId, eventType, aggregateId, aggregateVersion, payload, null);
        }
    }

    public record EventResult(int applied, int duplicates, int stale, int ignored, List<Rejected> rejected) {}

    /** An event this service can never apply as sent; the sender parks it. */
    public record Rejected(UUID eventId, String reason) {}

    public record ReconcileStatus(Instant at, Drift drift, String error) {
        static final ReconcileStatus NEVER = new ReconcileStatus(null, null, null);
    }

    public record Status(boolean graphAvailable, RecommendationGraph.Counts history, ReconcileStatus lastReconcile) {}

    public static class Unavailable extends RuntimeException {}
}
