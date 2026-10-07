// Apply with: cypher-shell -u neo4j -p "$NEO4J_PASSWORD" -f 001-constraints.cypher
CREATE CONSTRAINT travel_id IF NOT EXISTS FOR (n:Travel) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT destination_id IF NOT EXISTS FOR (n:Destination) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT stop_id IF NOT EXISTS FOR (n:DestinationStop) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT activity_id IF NOT EXISTS FOR (n:Activity) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT accommodation_id IF NOT EXISTS FOR (n:Accommodation) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT transport_id IF NOT EXISTS FOR (n:Transport) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT traveler_guard_id IF NOT EXISTS FOR (n:TravelerGuard) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT travel_event_id IF NOT EXISTS FOR (e:TravelEvent) REQUIRE e.eventId IS UNIQUE;
CREATE CONSTRAINT travel_event_version IF NOT EXISTS FOR (e:TravelEvent) REQUIRE (e.aggregateId, e.aggregateVersion) IS UNIQUE;
CREATE INDEX travel_dates IF NOT EXISTS FOR (n:Travel) ON (n.startDate, n.endDate);
CREATE INDEX travel_manager IF NOT EXISTS FOR (n:Travel) ON (n.managerId);
CREATE INDEX destination_country IF NOT EXISTS FOR (n:Destination) ON (n.countryCode);
CREATE INDEX participation_user IF NOT EXISTS FOR (n:Participation) ON (n.userId);

// Ownerless Phase 1 records are preserved for explicit review but hidden from normal reads.
MATCH (t:Travel) WHERE t.managerId IS NULL
REMOVE t:Travel
SET t:LegacyTravelNeedsReview;
