# Events and cross-store consistency

Let's Travel keeps data in three kinds of store: PostgreSQL (auth, users, payments,
engagement), Neo4j (travels, recommendations) and Elasticsearch (search). Each
record has exactly one owning service, and no request writes to two stores in one
transaction. Other stores receive changes as **events** and keep **derived copies**
(read models). This document defines how those events are written, delivered,
applied, retried and replayed, so every derived copy can be checked and rebuilt.

## Guarantees

1. **No lost changes.** An event is stored in the same local transaction as the
   change it describes (transactional outbox). If the transaction rolls back, the
   event never existed; if it commits, the event will be delivered.
2. **At-least-once delivery, exactly-once effect.** Consumers may see an event more
   than once. They record each applied `eventId` in the same transaction as its
   effect (inbox), so a repeat changes nothing.
3. **Per-aggregate order.** Events for one aggregate (one travel, one subscription)
   are applied in `aggregateVersion` order. Unrelated aggregates may interleave.
4. **Derived copies converge.** A projection may lag, but it can always be
   compared with its source (reconciliation) and rebuilt from it (rebuild).

Elasticsearch, the Neo4j recommendation graph and the dashboard projections are
derived. Losing any of them loses no business data.

## Event envelope

Every event, in every service, uses this envelope. It is serialized as JSON.

| Field | Type | Meaning |
| --- | --- | --- |
| `eventId` | UUID | Unique per event; the deduplication key |
| `eventType` | string | Past-tense name, e.g. `SubscriptionConfirmed` |
| `schemaVersion` | integer | Version of `payload` for this `eventType`, starting at 1 |
| `aggregateType` | string | e.g. `Travel`, `Subscription` |
| `aggregateId` | UUID | The changed aggregate |
| `aggregateVersion` | integer | Version after the change; increases by 1 per change |
| `occurredAt` | RFC 3339 instant | Commit time in the producer |
| `producer` | string | Owning service, e.g. `engagement-service` |
| `traceparent` | string | W3C trace context of the request that caused the change |
| `payload` | object | Event data; carries enough state for consumers to avoid calling back |

Rules for payloads:

- Carry the facts consumers need (a travel event carries the searchable snapshot),
  so a consumer never has to call the producer to apply an event.
- Never include passwords, tokens, payment credentials, report descriptions or
  contact details. Use IDs and display names only.
- Adding optional fields keeps the `schemaVersion`. Removing or changing a field
  needs a new `schemaVersion`, and producers emit both versions until every
  consumer has moved.

## Event catalog

| Producer | Event | Main consumers |
| --- | --- | --- |
| travel-service | `TravelPublished`, `TravelUpdated`, `TravelUnpublished`, `TravelDeleted` | search-service (index), engagement-service (price, dates, capacity and manager snapshot) |
| engagement-service | `SubscriptionReserved`, `SubscriptionConfirmed`, `SubscriptionPaymentFailed`, `SubscriptionCancelled`, `SubscriptionCompleted` (Phase 3, see [subscriptions](subscriptions.md)) | payment-service, travel-service (recommendation data), search-service (remaining places), dashboards |
| engagement-service | `FeedbackSubmitted` (Phase 6, see [feedback and reports](feedback-and-reports.md); IDs and rating only) | travel-service (recommendation weights), search-service (average rating) |
| engagement-service | `ReportFiled`, `ReportModerated` (Phase 6; IDs, category and status only, never the description) | dashboards, notifications (none subscribed yet) |
| payment-service | `SubscriptionPaymentSucceeded`, `SubscriptionPaymentFailed`, `SubscriptionPaymentRefunded` | engagement-service (confirm or release the place, income figures) |
| user-service | `UserProfileUpdated`, `UserRoleChanged`, `UserDeleted` | engagement-service (display names, deletion), search-service (manager names) |

Payload schemas are defined with the phase that first emits each event and are
listed in this file at that time.

## Delivery: outbox feeds

There is no message broker. Each producer exposes its outbox as an ordered,
private feed, and each consumer pulls from it.

```
 request ──► owning service
             ├─ local transaction: business change + outbox row
             │
             └─ feed sequencer (background, one at a time)
                  assigns feedPosition 1, 2, 3 … to committed rows
                                │
   GET /internal/events?after=<position>&limit=200   (machine token)
                                │
             consumer poller ───┘
             ├─ for each event, one local transaction:
             │     skip if eventId in inbox, else apply effect + record eventId
             └─ store the last applied position (its cursor)
```

Why pull instead of a broker:

- **No extra infrastructure.** A broker (Kafka, RabbitMQ) would be another
  stateful container to run, secure and monitor within the 11 GB CI budget.
- **Replay is built in.** A consumer rebuilds by resetting its cursor to 0;
  the producer needs no special replay support.
- **Back-pressure is natural.** A slow consumer only falls behind; it never
  overloads the producer, and its lag is one number (`head - cursor`).

The cost is polling latency (about one second) and producers must keep events
for as long as a rebuild may need them (below).

### Why a separate sequencer

Outbox rows from concurrent transactions can commit in a different order from
the one they were inserted in. If consumers paged by insertion order, they could
read past a row that commits a moment later and never see it. So rows get their
`feedPosition` only after commit, from a single sequencer that holds a lock
(a PostgreSQL advisory lock; in Neo4j, a single sequencer node updated in the
same transaction). The feed exposes only rows that have a position, in position
order. engagement-service's `outbox_event` table (migration V1) already has the
columns this needs; `published_at` records when the row received its position,
and the `feed_position` column is added with the first consumer in Phase 3.

### Consumers

- Poll every second while events are flowing, backing off to five seconds when
  the feed is empty.
- Apply each event in its own local transaction together with its inbox row and
  the new cursor value, so a crash never applies an event without recording it.
- Ignore an event whose `aggregateVersion` is not newer than the version already
  applied for that aggregate. Out-of-date events are recorded as processed and
  skipped.
- Elasticsearch has no transactions. search-service writes each document with
  external versioning (`version = aggregateVersion`, `version_type = external`),
  so a replay or a reordered write can never overwrite newer data, then advances
  its cursor.

### Failures, retries and parking

| Failure | Behaviour |
| --- | --- |
| Producer unreachable | Retry with exponential backoff (1 s doubling to 60 s, with jitter). Nothing is lost; the consumer just lags. |
| Temporary error applying an event (database down) | Roll back and retry the same event with the same backoff. |
| Permanent error (invalid payload, unknown `schemaVersion`) | After 5 attempts, move the event to the consumer's **parked events** table with the error, advance the cursor, and raise the `parked_events` metric. |

Parked events are the dead-letter queue. Operators can list them, fix the cause,
and re-apply them through an internal endpoint; re-applying is safe because of
the inbox. A parked event never blocks later events, so one bad payload cannot
stop a projection.

### Metrics

Each consumer reports `event_lag` (producer head minus cursor),
`event_apply_errors_total`, `parked_events` and `last_applied_at` per feed. The
readiness endpoint stays UP while lagging; lag is shown, not treated as an outage.

## Reconciliation

Projections are checked against their sources, not trusted blindly.

| Projection | Compared with | Check |
| --- | --- | --- |
| Elasticsearch travel index | travel-service bookable travels | Full rebuild every 5 minutes replaces the index with travel-service's current set (Phase 5, [search](search.md)) |
| Remaining places in the index | travel-service remaining capacity | Re-indexed on every reservation and release notification, and by the same rebuild |
| Dashboard income | payment-service succeeded and refunded payments | Sum per month and currency |
| Dashboard subscription counts | engagement-service subscriptions | Count per travel and status |
| Neo4j participation | engagement-service confirmed and completed subscriptions | Set of (traveler, travel) pairs |

Each owning service exposes a private snapshot endpoint for its side
(`GET /internal/snapshots/<name>`). A scheduled job runs each check nightly and on
demand, reports differences as a metric and a log entry, and repairs a difference
by re-requesting the aggregate's current state from its owner, never by guessing.

## Search index sync (Phase 5)

search-service does not poll a feed yet. The Neo4j `TravelEvent` markers have no
feed position and seat reservations do not create one, so the simplest correct design
was chosen instead (details in [search](search.md)):

1. After every committed travel change (create, update, publish, unpublish, delete,
   reserve or release a place) travel-service posts
   `POST /internal/search/index/travels/{id}` to search-service. The message carries
   only the ID and is fire-and-forget: a failure never fails the travel request.
2. search-service queues the ID and, once a second, fetches the travel's current state
   from `GET /internal/search/travels/{id}`. It indexes the travel when it is published
   and bookable, otherwise removes it. Applying the same ID twice gives the same
   result, so duplicates are harmless (idempotent). Failed IDs stay queued and retry.
3. Every 5 minutes (and at start-up, retried every 30 seconds after a failure) a full
   rebuild copies `GET /internal/search/travels` (all bookable travels) into a new
   versioned index and switches the `travels` alias atomically. This repairs any change
   whose notification was lost (search-service down, a destination renamed, a user
   deleted, a travel whose start date passed).

Writes use the travel version with `version_type=external_gte`, so a late write can
never replace newer data.

## Recommendation history delivery (Phase 7)

This is the first outbox that is actually published. Instead of the pull feed described above,
engagement-service **pushes** its outbox to its one consumer, travel-service:

1. `OutboxRelay` (every second) reads up to 100 unpublished rows, oldest first, and posts them as
   one batch of envelopes to `POST /internal/events` on travel-service (internal service token).
2. Only after a `200` does it set `published_at` on those rows. On any failure it increments
   `publish_attempts` and stops; the same rows are sent again on the next run, so nothing is lost
   while travel-service or Neo4j is down.
3. travel-service records each applied `eventId` (`ProcessedEvent` nodes, the inbox) and ignores a
   subscription event whose `aggregateVersion` is not newer than the one it has (`SubscriptionState`).
   Repeats and reordering are therefore harmless, including two relaying replicas.
4. A reconciliation every 10 minutes compares the graph with engagement-service's current history and
   repairs and reports any drift (details in [recommendations](recommendations.md)).

A push relay was chosen because there is a single consumer. If a second consumer needs these events
(for example dashboard projections in another service), `published_at` must become per consumer,
or the pull feed above must be implemented.

## Rebuild

Any projection can be rebuilt without downtime:

- **Elasticsearch.** Create a new versioned index (`travels-v<N+1>`), fill it from
  travel-service's published-travel snapshot, then replay the travel feed from the
  snapshot's position to catch up. When the lag is zero, switch the `travels`
  alias to the new index in one atomic alias update and delete the old index.
  Searches keep using the old index until the switch.
- **Neo4j recommendation data** and **dashboard projections.** Clear the derived
  data, reset the consumer cursor to 0 and replay the feeds. Feeds keep events for
  at least as long as the oldest data a projection shows (dashboards go back 24
  months), or a producer snapshot seeds the rebuild instead.

## Synchronous calls that remain

Events cover keeping copies in step. A few steps still call another service
directly, because the caller needs the answer right away:

- **Subscribing** reserves the place in travel-service, which owns capacity, through
  its private reservation endpoint (one atomic Neo4j transaction, see
  [subscriptions](subscriptions.md)). If travel-service is down, subscribing fails
  with `503` and nothing is reserved. Cancelling commits first and releases the
  place afterwards, retrying until travel-service confirms.
- **Paying** sends the subscription ID to payment-service; payment-service asks
  engagement-service (internal, machine token) to confirm the subscription is
  `PENDING_PAYMENT` and belongs to the caller before charging.
- **Token validation** calls auth-service, as today.

If a synchronous dependency is down, the request fails with `503
DEPENDENCY_UNAVAILABLE` before anything is changed.
