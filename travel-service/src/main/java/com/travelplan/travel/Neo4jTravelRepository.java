package com.travelplan.travel;

import com.travelplan.travel.TravelDomain.Accommodation;
import com.travelplan.travel.TravelDomain.Activity;
import com.travelplan.travel.TravelDomain.Destination;
import com.travelplan.travel.TravelDomain.Stop;
import com.travelplan.travel.TravelDomain.Transport;
import com.travelplan.travel.TravelDomain.Travel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.types.Node;

final class Neo4jTravelRepository {
    private static final String PROJECTION = " RETURN t AS travel, "
            + "[(t)-[:HAS_STOP]->(s:DestinationStop) | s] AS stops, "
            + "[(t)-[:HAS_ACTIVITY]->(a:Activity) | a] AS activities, "
            + "[(t)-[:HAS_ACCOMMODATION]->(h:Accommodation) | h] AS accommodations, "
            + "[(t)-[:HAS_TRANSPORT]->(r:Transport) | r] AS transports, "
            + "[(t)-[:HAS_PARTICIPANT]->(p:Participation) | p] AS participants";
    private static final String OWNED = "HAS_STOP|HAS_ACTIVITY|HAS_ACCOMMODATION|HAS_TRANSPORT|HAS_PARTICIPANT";
    private final Driver driver;

    Neo4jTravelRepository(Driver driver) {
        this.driver = driver;
        initializeSchema();
    }

    private void initializeSchema() {
        try (Session session = driver.session()) {
            for (String label : List.of("Travel", "Destination", "DestinationStop", "Activity", "Accommodation", "Transport", "Participation", "TravelerGuard")) {
                session.run("CREATE CONSTRAINT " + label.toLowerCase(Locale.ROOT)
                        + "_id IF NOT EXISTS FOR (n:" + label + ") REQUIRE n.id IS UNIQUE").consume();
            }
            session.run("MATCH (d:Destination) WHERE d.normalizedName IS NULL OR d.normalizedCountry IS NULL "
                    + "SET d.normalizedName=toLower(trim(d.name)), d.normalizedCountry=toLower(trim(d.country))").consume();
            session.run("CREATE CONSTRAINT destination_name_country IF NOT EXISTS FOR (d:Destination) "
                    + "REQUIRE (d.normalizedName, d.normalizedCountry) IS UNIQUE").consume();
        }
    }

    List<Destination> destinations() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("MATCH (d:Destination) RETURN d ORDER BY d.createdAt, d.id")
                    .list(record -> destination(record.get("d").asNode())));
        }
    }

    Destination destination(UUID id) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("MATCH (d:Destination {id:$id}) RETURN d", Map.of("id", id.toString()));
                if (!result.hasNext()) throw new TravelStore.NotFound();
                return destination(result.single().get("d").asNode());
            });
        }
    }

    Destination createDestination(Destination destination) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                String normalizedName = normalize(destination.name());
                String normalizedCountry = normalize(destination.country());
                if (tx.run("MATCH (d:Destination {normalizedName:$name, normalizedCountry:$country}) RETURN d",
                        Map.of("name", normalizedName, "country", normalizedCountry)).hasNext())
                    throw new TravelStore.Conflict("DESTINATION_EXISTS");
                tx.run("CREATE (d:Destination {id:$id, name:$name, country:$country, normalizedName:$normalizedName, "
                                + "normalizedCountry:$normalizedCountry, createdAt:$createdAt})",
                        Map.of("id", destination.id().toString(), "name", destination.name(), "country", destination.country(),
                                "normalizedName", normalizedName, "normalizedCountry", normalizedCountry,
                                "createdAt", destination.createdAt().toString())).consume();
                return destination;
            });
        } catch (ClientException ex) {
            if (constraintViolation(ex)) throw new TravelStore.Conflict("DESTINATION_EXISTS");
            throw ex;
        }
    }

    Destination updateDestination(Destination replacement) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                var current = tx.run("MATCH (d:Destination {id:$id}) "
                        + "SET d.lockVersion=coalesce(d.lockVersion,0)+1 RETURN d", Map.of("id", replacement.id().toString()));
                if (!current.hasNext()) throw new TravelStore.NotFound();
                Destination old = destination(current.single().get("d").asNode());
                String normalizedName = normalize(replacement.name());
                String normalizedCountry = normalize(replacement.country());
                if (tx.run("MATCH (d:Destination {normalizedName:$name, normalizedCountry:$country}) "
                                + "WHERE d.id <> $id RETURN d",
                        Map.of("name", normalizedName, "country", normalizedCountry, "id", replacement.id().toString())).hasNext())
                    throw new TravelStore.Conflict("DESTINATION_EXISTS");
                tx.run("MATCH (d:Destination {id:$id}) SET d.name=$name, d.country=$country, "
                                + "d.normalizedName=$normalizedName, d.normalizedCountry=$normalizedCountry",
                        Map.of("id", replacement.id().toString(), "name", replacement.name(), "country", replacement.country(),
                                "normalizedName", normalizedName, "normalizedCountry", normalizedCountry)).consume();
                return new Destination(replacement.id(), replacement.name(), replacement.country(), old.createdAt());
            });
        } catch (ClientException ex) {
            if (constraintViolation(ex)) throw new TravelStore.Conflict("DESTINATION_EXISTS");
            throw ex;
        }
    }

    void deleteDestination(UUID id) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                if (!tx.run("MATCH (d:Destination {id:$id}) "
                        + "SET d.lockVersion=coalesce(d.lockVersion,0)+1 RETURN d", Map.of("id", id.toString())).hasNext())
                    throw new TravelStore.NotFound();
                if (tx.run("MATCH (:DestinationStop)-[:AT]->(d:Destination {id:$id}) RETURN d LIMIT 1",
                        Map.of("id", id.toString())).hasNext())
                    throw new TravelStore.Conflict("DESTINATION_IN_USE");
                tx.run("MATCH (d:Destination {id:$id}) DELETE d", Map.of("id", id.toString())).consume();
                return null;
            });
        }
    }

    List<Travel> travels() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("MATCH (t:Travel)" + PROJECTION + " ORDER BY t.createdAt, t.id")
                    .list(Neo4jTravelRepository::travel));
        }
    }

    Travel travel(UUID id) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("MATCH (t:Travel {id:$id})" + PROJECTION, Map.of("id", id.toString()));
                if (!result.hasNext()) throw new TravelStore.NotFound();
                return travel(result.single());
            });
        }
    }

    Travel save(Travel travel) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                tx.run("CREATE (t:Travel {id:$id, name:$name, startDate:$start, endDate:$end, "
                                + "durationDays:$duration, createdAt:$createdAt})",
                        rootProperties(travel)).consume();
                createItinerary(tx, travel);
                return travel;
            });
        }
    }

    Travel update(Travel travel) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                if (!tx.run("MATCH (t:Travel {id:$id}) "
                        + "SET t.lockVersion=coalesce(t.lockVersion,0)+1 RETURN t", Map.of("id", travel.id().toString())).hasNext())
                    throw new TravelStore.NotFound();
                tx.run("MATCH (t:Travel {id:$id})-[r:" + OWNED + "]->(owned) "
                        + "WHERE NOT owned:Participation DETACH DELETE owned", Map.of("id", travel.id().toString())).consume();
                tx.run("MATCH (t:Travel {id:$id}) SET t.name=$name, t.startDate=$start, t.endDate=$end, "
                                + "t.durationDays=$duration", rootProperties(travel)).consume();
                createItinerary(tx, travel);
                return travel(tx.run("MATCH (t:Travel {id:$id})" + PROJECTION,
                        Map.of("id", travel.id().toString())).single());
            });
        }
    }

    Travel addParticipant(UUID travelId, UUID travelerId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                var guard = tx.run("MERGE (g:TravelerGuard {id:$travelerId}) "
                        + "SET g.lockVersion=coalesce(g.lockVersion,0)+1 RETURN coalesce(g.deleted,false) AS deleted",
                        Map.of("travelerId", travelerId.toString())).single();
                if (guard.get("deleted").asBoolean()) throw new TravelStore.Conflict("TRAVELER_REMOVED");
                if (!tx.run("MATCH (t:Travel {id:$id}) "
                        + "SET t.lockVersion=coalesce(t.lockVersion,0)+1 RETURN t", Map.of("id", travelId.toString())).hasNext())
                    throw new TravelStore.NotFound();
                String participationId = UUID.nameUUIDFromBytes((travelId + ":" + travelerId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                tx.run("MATCH (t:Travel {id:$travelId}) MERGE (p:Participation {id:$id}) "
                                + "SET p.travelerId=$travelerId MERGE (t)-[:HAS_PARTICIPANT]->(p)",
                        Map.of("travelId", travelId.toString(), "id", participationId, "travelerId", travelerId.toString())).consume();
                return null;
            });
        }
        return travel(travelId);
    }

    void removeParticipant(UUID travelId, UUID travelerId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                if (!tx.run("MATCH (t:Travel {id:$id}) "
                        + "SET t.lockVersion=coalesce(t.lockVersion,0)+1 RETURN t", Map.of("id", travelId.toString())).hasNext())
                    throw new TravelStore.NotFound();
                tx.run("MATCH (t:Travel {id:$travelId})-[:HAS_PARTICIPANT]->(p:Participation {travelerId:$travelerId}) "
                                + "DETACH DELETE p",
                        Map.of("travelId", travelId.toString(), "travelerId", travelerId.toString())).consume();
                return null;
            });
        }
    }

    void removeParticipants(Set<UUID> travelerIds) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                List<String> ordered = travelerIds.stream().map(UUID::toString).sorted().toList();
                for (String travelerId : ordered) {
                    tx.run("MERGE (g:TravelerGuard {id:$travelerId}) SET g.deleted=true",
                            Map.of("travelerId", travelerId)).consume();
                    tx.run("MATCH (p:Participation {travelerId:$travelerId}) DETACH DELETE p",
                            Map.of("travelerId", travelerId)).consume();
                }
                return null;
            });
        }
    }

    void delete(UUID id) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                if (!tx.run("MATCH (t:Travel {id:$id}) "
                        + "SET t.lockVersion=coalesce(t.lockVersion,0)+1 RETURN t", Map.of("id", id.toString())).hasNext())
                    throw new TravelStore.NotFound();
                tx.run("MATCH (t:Travel {id:$id})-[:" + OWNED + "]->(owned) DETACH DELETE owned",
                        Map.of("id", id.toString())).consume();
                tx.run("MATCH (t:Travel {id:$id}) DETACH DELETE t", Map.of("id", id.toString())).consume();
                return null;
            });
        }
    }

    private static void createItinerary(TransactionContext tx, Travel travel) {
        for (Stop stop : travel.stops()) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", stop.id().toString());
            values.put("destinationId", stop.destinationId().toString());
            values.put("position", stop.position());
            values.put("arrivalDate", date(stop.arrivalDate()));
            values.put("departureDate", date(stop.departureDate()));
            if (!tx.run("MATCH (t:Travel {id:$travelId}), (d:Destination {id:$destinationId}) "
                            + "SET d.lockVersion=coalesce(d.lockVersion,0)+1 "
                            + "CREATE (t)-[:HAS_STOP]->(s:DestinationStop) SET s=$properties "
                            + "CREATE (s)-[:AT]->(d) RETURN s.id AS id",
                    Map.of("travelId", travel.id().toString(), "destinationId", stop.destinationId().toString(),
                            "properties", values)).hasNext())
                throw new TravelStore.Conflict("DESTINATION_REQUIRED");
        }
        int sequence = 0;
        for (Activity activity : travel.activities()) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", activity.id().toString());
            values.put("name", activity.name());
            values.put("description", activity.description());
            values.put("date", date(activity.date()));
            values.put("sequence", sequence++);
            createOwned(tx, travel.id(), "HAS_ACTIVITY", "Activity", values);
        }
        sequence = 0;
        for (Accommodation stay : travel.accommodations()) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", stay.id().toString());
            values.put("name", stay.name());
            values.put("address", stay.address());
            values.put("checkIn", date(stay.checkIn()));
            values.put("checkOut", date(stay.checkOut()));
            values.put("sequence", sequence++);
            createOwned(tx, travel.id(), "HAS_ACCOMMODATION", "Accommodation", values);
        }
        sequence = 0;
        for (Transport transport : travel.transports()) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", transport.id().toString());
            values.put("mode", transport.mode());
            values.put("departure", transport.departure());
            values.put("arrival", transport.arrival());
            values.put("date", date(transport.date()));
            values.put("sequence", sequence++);
            createOwned(tx, travel.id(), "HAS_TRANSPORT", "Transport", values);
        }
    }

    private static void createOwned(TransactionContext tx, UUID travelId, String relationship, String label,
                                    Map<String, Object> properties) {
        tx.run("MATCH (t:Travel {id:$travelId}) CREATE (t)-[:" + relationship + "]->(n:" + label + ") "
                        + "SET n=$properties",
                Map.of("travelId", travelId.toString(), "properties", properties)).consume();
    }

    private static Travel travel(Record record) {
        Node root = record.get("travel").asNode();
        List<Stop> stops = record.get("stops").asList(value -> value.asNode()).stream()
                .sorted(Comparator.comparingInt(node -> node.get("position").asInt()))
                .map(node -> new Stop(uuid(node, "id"), destinationForStop(node), node.get("position").asInt(),
                        optionalDate(node, "arrivalDate"), optionalDate(node, "departureDate"))).toList();
        if (stops.isEmpty()) throw new TravelStore.Conflict("INCOMPLETE_LEGACY_TRAVEL");
        List<Activity> activities = record.get("activities").asList(value -> value.asNode()).stream()
                .sorted(Comparator.comparingInt(node -> node.get("sequence").asInt(0)))
                .map(node -> new Activity(uuid(node, "id"), node.get("name").asString(),
                        node.get("description").asString(null), optionalDate(node, "date"))).toList();
        List<Accommodation> stays = record.get("accommodations").asList(value -> value.asNode()).stream()
                .sorted(Comparator.comparingInt(node -> node.get("sequence").asInt(0)))
                .map(node -> new Accommodation(uuid(node, "id"), node.get("name").asString(),
                        node.get("address").asString(), optionalDate(node, "checkIn"), optionalDate(node, "checkOut"))).toList();
        List<Transport> transports = record.get("transports").asList(value -> value.asNode()).stream()
                .sorted(Comparator.comparingInt(node -> node.get("sequence").asInt(0)))
                .map(node -> new Transport(uuid(node, "id"), node.get("mode").asString(),
                        node.get("departure").asString(), node.get("arrival").asString(), optionalDate(node, "date"))).toList();
        Set<UUID> travelers = new LinkedHashSet<>();
        record.get("participants").asList(value -> value.asNode()).forEach(node -> travelers.add(uuid(node, "travelerId")));
        return new Travel(uuid(root, "id"), root.get("name").asString(), LocalDate.parse(root.get("startDate").asString()),
                LocalDate.parse(root.get("endDate").asString()), root.get("durationDays").asLong(),
                stops, activities, stays, transports, travelers, createdAt(root));
    }

    private static UUID destinationForStop(Node stop) {
        return UUID.fromString(stop.get("destinationId").asString());
    }

    private static Destination destination(Node node) {
        return new Destination(uuid(node, "id"), node.get("name").asString(), node.get("country").asString(), createdAt(node));
    }

    private static Instant createdAt(Node node) {
        return node.get("createdAt").isNull() ? Instant.EPOCH : Instant.parse(node.get("createdAt").asString());
    }

    private static UUID uuid(Node node, String key) { return UUID.fromString(node.get(key).asString()); }
    private static String date(LocalDate value) { return value == null ? null : value.toString(); }
    private static LocalDate optionalDate(Node node, String key) {
        return node.get(key).isNull() ? null : LocalDate.parse(node.get(key).asString());
    }
    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static boolean constraintViolation(ClientException ex) {
        return ex.code().contains("ConstraintValidationFailed");
    }
    private static Map<String, Object> rootProperties(Travel travel) {
        return Map.of("id", travel.id().toString(), "name", travel.name(),
                "start", date(travel.startDate()), "end", date(travel.endDate()),
                "duration", travel.durationDays(), "createdAt", travel.createdAt().toString());
    }
}
