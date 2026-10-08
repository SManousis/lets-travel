# Personalized recommendations (Phase 7)

How a traveler gets travel suggestions from their own trips and ratings, where the data lives in
Neo4j, how it is kept in step with engagement-service, and what happens when something fails.
API contract: [api/recommendations.yaml](api/recommendations.yaml).

## 1. What the traveler sees

`GET /api/recommendations?limit=1..20` (any signed-in role) returns up to `limit` travels with a
`strategy` and, for each travel, a score and up to three **reasons**: field values from the
traveler's own history that the travel matches, for example `{field: ACTIVITY_CATEGORY, value:
CULTURE}`. The dashboard shows them as "You enjoyed culture activities". Reasons never mention
other travelers.

| Strategy | When | Order |
| --- | --- | --- |
| `PERSONALIZED` | The traveler has at least one confirmed or completed trip not rated 1 star | Score, see section 3 |
| `POPULAR` | No usable history (a new account, or only 1-star trips) | Places taken, then average rating, then start date, then ID |

## 2. The four travel fields

| Field | Where it comes from |
| --- | --- |
| Destination | The travel's stops (destination names) |
| Activity category | `category` on each activity: CULTURE, NATURE, ADVENTURE, BEACH, FOOD, NIGHTLIFE, WELLNESS, SPORT |
| Transport mode | Each transport's `mode`, compared without regard to case or spaces (suggested: PLANE, TRAIN, BUS, CAR, BOAT) |
| Accommodation category | `category` on each stay: HOTEL, HOSTEL, APARTMENT, RESORT, CAMPING |

Managers set the categories in the itinerary editor. They are optional, so travels created before
Phase 7 still work; a travel without categories can only match on destination and transport.

## 3. Scoring (deterministic)

Implemented in `RecommendationEngine` (plain Java, unit-tested without a database).

1. **Weight of a past trip** = `1 + 0.5 x (rating - 3)`: 5 stars weigh 2.0, 4 stars 1.5, 3 stars or
   no rating 1.0, 2 stars 0.5, 1 star 0. Negative feedback therefore adds little or nothing.
2. **Preference** for a value (destination "Rome", activity "CULTURE", ...) = the sum of the weights of
   past trips that have it, divided by the largest preference in the same field (0 to 1).
3. **Score** of a candidate = `0.35 x destination + 0.25 x activity + 0.20 x transport + 0.20 x
   accommodation`, each term being the strongest preference among the candidate's values for that
   field. Only travels with a score above 0 are listed.
4. **Ties** break on the travel's average rating (higher first), then start date (sooner first),
   then travel ID, so the same data always gives the same list.
5. **Variety:** at most two travels with the same first destination are listed while other
   matching travels remain; the held-back ones fill any remaining places at the end.

**Eligible travels** (both strategies): published, starting after today (UTC), places left, not
managed by the caller, and not already booked or taken by the caller.

## 4. Data in Neo4j

```
(:TravelHistory {userId})-[:TOOK_PART {subscriptionId, status}]->(:Travel)   confirmed or completed trips
(:TravelHistory {userId})-[:RATED {rating, feedbackId}]->(:Travel)          the traveler's rating
(:SubscriptionState {id, userId, travelId, status, version})                newest subscription version applied
(:ProcessedEvent {id})                                                      events already applied (inbox)
```

Only IDs, statuses and ratings are stored: no names, contact details or feedback text. A rating is
stored separately from participation, so it is kept whichever event arrives first.

## 5. Keeping the graph in step with engagement-service

engagement-service owns subscriptions and feedback (PostgreSQL). Two mechanisms copy them into the
graph (see also [events](events.md)):

1. **Event delivery (seconds).** `OutboxRelay` in engagement-service sends unpublished outbox events,
   oldest first, in batches of up to 100 to `POST /internal/events` on travel-service, and marks
   them published only after travel-service accepted the batch. If travel-service or Neo4j is down,
   the events stay queued and are sent again. travel-service applies each event once (inbox:
   `ProcessedEvent`) and ignores a subscription event older than the version it already has, so a
   repeated or late event can never undo a newer change (for example re-adding a cancelled booking).
2. **Reconciliation (every 10 minutes, and on demand).** `HistoryReconciler` reads engagement-service's
   current history (`GET /internal/recommendation-history`: confirmed and completed subscriptions with
   ratings) and makes the graph match it exactly. It reports the **drift** it repaired: participations
   added, participations removed and ratings changed. Zero means the events had already done
   everything.

**Rebuild from scratch:** delete the history (`MATCH (n:TravelHistory) DETACH DELETE n`), then run the
reconciliation (`POST /api/admin/recommendations/reconcile`, admin only). `GET
/api/admin/recommendations/status` shows the history counts and the last reconciliation result.

**Deleted users:** user deletion sends the account ID to travel-service's cleanup, which removes the
user's seat reservations and their whole recommendation history.

## 6. When something fails

| Failure | Effect |
| --- | --- |
| Neo4j down | `GET /api/recommendations` answers `503 DEPENDENCY_UNAVAILABLE`; the dashboard shows a short notice and the rest of the page keeps working. Events stay queued in engagement-service. |
| travel-service down | Events stay queued (`publish_attempts` counts the failed deliveries) and are sent when it is back. |
| engagement-service down | Recommendations keep working from the graph; the reconciliation retries on its next run. |
| An event was lost | The next reconciliation repairs it and reports it as drift. |

## 7. Evidence

| Behaviour | Test |
| --- | --- |
| Two travelers with different histories get different, explained, reproducible rankings | `RecommendationServiceTest.twoTravelersGetDistinctExplainedRecommendations`, `RecommendationEngineTest.twoTravelersWithDifferentHistoriesGetDifferentExplainedRankings` |
| Cold start, negative feedback, ties, variety, determinism | `RecommendationEngineTest` |
| Eligibility (draft, started, full, booked, taken, own travel) | `RecommendationGraphTest.onlyBookableTravelsTheCallerHasNotTakenAreCandidates` |
| Duplicate and out-of-order events, cancelled bookings | `RecommendationGraphTest`, `RecommendationServiceTest` |
| Deleted users forgotten | `RecommendationGraphTest.deletingAUserRemovesTheirHistoryButNotOthers` |
| Drift repaired and reported | `RecommendationGraphTest.reconciliationRepairsDriftAndThenFindsNothing` |
| Neo4j unavailable | `RecommendationServiceTest.withoutNeo4jEverythingReportsUnavailable`, `Recommendations.test.tsx` |
| Relay order, retry after outage, history snapshot | `EventRelayTest` |
