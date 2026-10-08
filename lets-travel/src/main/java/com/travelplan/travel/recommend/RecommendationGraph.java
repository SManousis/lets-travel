package com.travelplan.travel.recommend;

import com.travelplan.travel.recommend.RecommendationEngine.History;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;

/**
 * Each traveler's travel history in the travel graph, kept up to date from engagement-service events
 * and repaired by a periodic reconciliation. Only IDs, statuses and ratings are stored: no names,
 * contact details or feedback text.
 *
 * <pre>
 * (:TravelHistory {userId})-[:TOOK_PART {subscriptionId, status}]->(:Travel)   confirmed or completed
 * (:TravelHistory {userId})-[:RATED {rating, feedbackId}]->(:Travel)          the traveler's rating
 * (:SubscriptionState {id, userId, travelId, status, version})                newest version applied
 * (:ProcessedEvent {id})                                                      inbox: applied event IDs
 * </pre>
 */
public class RecommendationGraph {
    static final Set<String> ACTIVE = Set.of("CONFIRMED", "COMPLETED");

    private static final String FEATURES = "t.id AS id, t.name AS title, t.startDate AS startDate, t.endDate AS endDate, "
            + "t.price AS price, t.currency AS currency, "
            // Pattern variables are prefixed with f_ so they never capture a variable of the outer query.
            + "[(t)-[:HAS_STOP]->(:DestinationStop)-[:AT]->(f_place:Destination) | toUpper(trim(f_place.name))] AS destinations, "
            + "[(t)-[:HAS_ACTIVITY]->(f_act:Activity) WHERE f_act.category IS NOT NULL | f_act.category] AS activities, "
            + "[(t)-[:HAS_TRANSPORT]->(f_ride:Transport) WHERE f_ride.mode IS NOT NULL | toUpper(trim(f_ride.mode))] AS transports, "
            + "[(t)-[:HAS_ACCOMMODATION]->(f_stay:Accommodation) WHERE f_stay.category IS NOT NULL | f_stay.category] AS stays, "
            + "[(:TravelHistory)-[f_rated:RATED]->(t) | f_rated.rating] AS ratings, "
            + "COUNT { (t)-[:HAS_PARTICIPANT]->(:Participation) } AS taken";

    private final Driver driver;

    public RecommendationGraph(Driver driver) {
        this.driver = driver;
        try (Session session = driver.session()) {
            session.run("CREATE CONSTRAINT travel_history_user IF NOT EXISTS FOR (h:TravelHistory) REQUIRE h.userId IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT subscription_state_id IF NOT EXISTS FOR (s:SubscriptionState) REQUIRE s.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT processed_event_id IF NOT EXISTS FOR (e:ProcessedEvent) REQUIRE e.id IS UNIQUE").consume();
        }
    }

    public enum Outcome { APPLIED, DUPLICATE, STALE }

    /** A subscription moved to {@code status}; only CONFIRMED and COMPLETED count as history. */
    public Outcome applySubscription(UUID eventId, UUID subscriptionId, UUID userId, UUID travelId, String status, long version) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                if (!firstDelivery(tx, eventId)) return Outcome.DUPLICATE;
                Record state = tx.run("MERGE (s:SubscriptionState {id:$id}) ON CREATE SET s.version = -1 RETURN s.version AS version",
                        Map.of("id", subscriptionId.toString())).single();
                if (state.get("version").asLong() >= version) return Outcome.STALE;
                tx.run("MATCH (s:SubscriptionState {id:$id}) SET s.userId=$user, s.travelId=$travel, s.status=$status, s.version=$version",
                        Map.of("id", subscriptionId.toString(), "user", userId.toString(), "travel", travelId.toString(),
                                "status", status, "version", version)).consume();
                setParticipation(tx, subscriptionId, userId, travelId, ACTIVE.contains(status) ? status : null);
                return Outcome.APPLIED;
            });
        }
    }

    /** The traveler rated a travel they took part in. Ratings are stored independently of event order. */
    public Outcome applyFeedback(UUID eventId, UUID feedbackId, UUID userId, UUID travelId, int rating) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                if (!firstDelivery(tx, eventId)) return Outcome.DUPLICATE;
                setRating(tx, userId, travelId, feedbackId, rating);
                return Outcome.APPLIED;
            });
        }
    }

    /** Removes everything known about these users (account deletion). */
    public void forgetUsers(Set<UUID> userIds) {
        List<String> ids = userIds.stream().map(UUID::toString).toList();
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (h:TravelHistory) WHERE h.userId IN $ids DETACH DELETE h", Map.of("ids", ids)).consume();
                tx.run("MATCH (s:SubscriptionState) WHERE s.userId IN $ids DELETE s", Map.of("ids", ids)).consume();
                return null;
            });
        }
    }

    /**
     * Makes the graph match engagement-service's current history exactly and reports how much differed.
     * Repairs anything a lost or failed event left behind; running it twice changes nothing the second time.
     */
    public Drift reconcile(List<HistoryRow> snapshot) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                Map<String, HistoryRow> wanted = new HashMap<>();
                Map<String, Integer> wantedRatings = new HashMap<>();
                for (HistoryRow row : snapshot) {
                    if (ACTIVE.contains(row.status())) wanted.put(row.subscriptionId().toString(), row);
                    if (row.rating() != null) wantedRatings.put(row.userId() + "|" + row.travelId(), row.rating());
                }
                Set<String> present = new HashSet<>(tx.run("MATCH (:TravelHistory)-[r:TOOK_PART]->(:Travel) RETURN r.subscriptionId AS id")
                        .list(record -> record.get("id").asString()));
                int removed = 0;
                for (String id : present) {
                    if (wanted.containsKey(id)) continue;
                    tx.run("MATCH (:TravelHistory)-[r:TOOK_PART {subscriptionId:$id}]->() DELETE r", Map.of("id", id)).consume();
                    removed++;
                }
                int added = 0;
                for (HistoryRow row : wanted.values()) {
                    tx.run("MERGE (s:SubscriptionState {id:$id}) ON CREATE SET s.version = -1 "
                                    + "SET s.userId=$user, s.travelId=$travel, s.status=$status, "
                                    + "s.version = CASE WHEN s.version > $version THEN s.version ELSE $version END",
                            Map.of("id", row.subscriptionId().toString(), "user", row.userId().toString(),
                                    "travel", row.travelId().toString(), "status", row.status(), "version", row.version())).consume();
                    if (!present.contains(row.subscriptionId().toString())
                            && setParticipation(tx, row.subscriptionId(), row.userId(), row.travelId(), row.status())) added++;
                }
                Map<String, Integer> ratings = new HashMap<>();
                tx.run("MATCH (h:TravelHistory)-[r:RATED]->(t:Travel) RETURN h.userId AS user, t.id AS travel, r.rating AS rating")
                        .forEachRemaining(record -> ratings.put(record.get("user").asString() + "|" + record.get("travel").asString(),
                                record.get("rating").asInt()));
                int ratingsChanged = 0;
                for (Map.Entry<String, Integer> rating : ratings.entrySet()) {
                    if (wantedRatings.containsKey(rating.getKey())) continue;
                    String[] key = rating.getKey().split("\\|");
                    tx.run("MATCH (:TravelHistory {userId:$user})-[r:RATED]->(:Travel {id:$travel}) DELETE r",
                            Map.of("user", key[0], "travel", key[1])).consume();
                    ratingsChanged++;
                }
                for (Map.Entry<String, Integer> rating : wantedRatings.entrySet()) {
                    if (Objects.equals(ratings.get(rating.getKey()), rating.getValue())) continue;
                    String[] key = rating.getKey().split("\\|");
                    if (setRating(tx, UUID.fromString(key[0]), UUID.fromString(key[1]), null, rating.getValue())) ratingsChanged++;
                }
                return new Drift(added, removed, ratingsChanged);
            });
        }
    }

    /** The traveler's confirmed and completed travels, with their own rating when they gave one. */
    public List<History> history(UUID userId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("MATCH (h:TravelHistory {userId:$user})-[:TOOK_PART]->(t:Travel) "
                            + "OPTIONAL MATCH (h)-[mine:RATED]->(t) "
                            + "RETURN mine.rating AS mine, " + FEATURES + " ORDER BY t.id",
                    Map.of("user", userId.toString())).list(record -> new History(features(record),
                    record.get("mine").isNull() ? null : record.get("mine").asInt())));
        }
    }

    /**
     * Travels that may be recommended: published, starting after {@code today}, with places left, not managed
     * by the caller, and not already booked or taken by them.
     */
    public List<TravelFeatures> candidates(UUID userId, LocalDate today) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("MATCH (t:Travel) WHERE t.status = 'PUBLISHED' AND t.startDate > $today "
                            + "AND t.managerId <> $user "
                            + "AND NOT EXISTS { (t)-[:HAS_PARTICIPANT]->(:Participation {travelerId:$user}) } "
                            + "AND NOT EXISTS { (:TravelHistory {userId:$user})-[:TOOK_PART]->(t) } "
                            + "AND COUNT { (t)-[:HAS_PARTICIPANT]->(:Participation) } < t.capacity "
                            + "RETURN " + FEATURES + " ORDER BY t.id",
                    Map.of("user", userId.toString(), "today", today.toString())).list(RecommendationGraph::features));
        }
    }

    public Counts counts() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record record = tx.run("RETURN COUNT { (:TravelHistory) } AS travelers, "
                        + "COUNT { ()-[:TOOK_PART]->() } AS participations, COUNT { ()-[:RATED]->() } AS ratings").single();
                return new Counts(record.get("travelers").asLong(), record.get("participations").asLong(), record.get("ratings").asLong());
            });
        }
    }

    private static boolean firstDelivery(TransactionContext tx, UUID eventId) {
        return tx.run("MERGE (e:ProcessedEvent {id:$id}) ON CREATE SET e.at = $at, e.fresh = true "
                        + "WITH e, coalesce(e.fresh, false) AS fresh REMOVE e.fresh RETURN fresh",
                Map.of("id", eventId.toString(), "at", Instant.now().toString())).single().get("fresh").asBoolean();
    }

    /** Sets or removes the TOOK_PART relationship; returns whether the travel exists in the graph. */
    private static boolean setParticipation(TransactionContext tx, UUID subscriptionId, UUID userId, UUID travelId, String activeStatus) {
        tx.run("MATCH (:TravelHistory)-[r:TOOK_PART {subscriptionId:$id}]->() DELETE r", Map.of("id", subscriptionId.toString())).consume();
        if (activeStatus == null) return false;
        return tx.run("MATCH (t:Travel {id:$travel}) MERGE (h:TravelHistory {userId:$user}) "
                        + "MERGE (h)-[r:TOOK_PART {subscriptionId:$id}]->(t) SET r.status = $status RETURN t.id",
                Map.of("travel", travelId.toString(), "user", userId.toString(), "id", subscriptionId.toString(),
                        "status", activeStatus)).hasNext();
    }

    private static boolean setRating(TransactionContext tx, UUID userId, UUID travelId, UUID feedbackId, int rating) {
        Map<String, Object> parameters = new HashMap<>(Map.of("user", userId.toString(), "travel", travelId.toString(), "rating", rating));
        parameters.put("feedback", feedbackId == null ? null : feedbackId.toString());
        return tx.run("MATCH (t:Travel {id:$travel}) MERGE (h:TravelHistory {userId:$user}) MERGE (h)-[r:RATED]->(t) "
                + "SET r.rating = $rating, r.feedbackId = coalesce($feedback, r.feedbackId) RETURN t.id", parameters).hasNext();
    }

    private static TravelFeatures features(Record record) {
        List<Integer> ratings = record.get("ratings").asList(Value::asInt);
        Double average = ratings.isEmpty() ? null
                : Math.round(ratings.stream().mapToInt(Integer::intValue).average().orElse(0) * 10) / 10.0;
        return new TravelFeatures(UUID.fromString(record.get("id").asString()), record.get("title").asString(),
                LocalDate.parse(record.get("startDate").asString()), LocalDate.parse(record.get("endDate").asString()),
                record.get("price").isNull() ? null : new BigDecimal(record.get("price").asString()),
                record.get("currency").asString(null), strings(record.get("destinations")), strings(record.get("activities")),
                strings(record.get("transports")), strings(record.get("stays")), average, record.get("taken").asInt());
    }

    private static Set<String> strings(Value list) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : list.asList(Value::asString)) {
            if (value != null && !value.isBlank()) values.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return values;
    }

    /** One subscription in engagement-service's current history, with the traveler's rating of that travel. */
    public record HistoryRow(UUID subscriptionId, UUID userId, UUID travelId, String status, long version, Integer rating) {}

    /** What a reconciliation had to change: zero everywhere means the graph was already in step. */
    public record Drift(int participationsAdded, int participationsRemoved, int ratingsChanged) {
        public int total() {
            return participationsAdded + participationsRemoved + ratingsChanged;
        }
    }

    public record Counts(long travelers, long participations, long ratings) {}
}
