# Manager-Owned Travel Offerings Design

**Date:** 2026-10-07
**Phase:** 2 — Manager-owned travel offerings and public profiles

## Intent

Complete Phase 2 of the Let's Travel roadmap so the repository can demonstrate the
manager-listing audit journey. Two travel managers must be able to manage independent
travel offerings without seeing or mutating each other's private listings, while
travelers see only eligible published offerings and administrators retain global
oversight.

This is a learning project optimized for a deterministic audit. Existing ownerless
travel data does not need to remain available as a normal offering; it is quarantined
for explicit review instead of being assigned to an arbitrary manager.

## Scope

Phase 2 adds:

- immutable manager ownership for travel offerings;
- a complete listing model with description, capacity, price, currency and lifecycle;
- manager-owned CRUD plus explicit publish and unpublish operations;
- administrator override and traveler-safe published browsing;
- publication and itinerary validation;
- versioned travel lifecycle events stored atomically with Neo4j changes;
- a public manager page served by engagement-service;
- focused unit, HTTP authorization, Neo4j integration and cross-service tests;
- matching OpenAPI, architecture and data-model documentation.

Subscriptions, payments, feedback entry, reports, recommendations, dashboards and
role-specific UI journeys remain in their later phases. Phase 2 exposes the fields and
internal seams those phases require.

## Service Responsibilities

### travel-service

The existing `lets-travel` Maven directory continues to build the `travel-service`
artifact. It is the source of truth for travel listings and their itinerary graphs.
It owns listing lifecycle, manager ownership, bookability fields, validation, and
travel event markers.

### user-service

User-service remains the source of truth for manager identity and safe public profile
data. A private internal endpoint returns a minimal active-manager summary after
validating the internal service token. It never exposes email, credentials, passport
data, or account-administration fields to the public manager page.

### engagement-service

Engagement-service owns the public manager-page endpoint. In Phase 2 it composes the
safe manager summary from user-service with completed-travel statistics from
travel-service. Ratings and upheld-report counts are returned as empty/zero because
their source records do not exist until Phase 6. The response shape remains compatible
with later event-driven projections.

The public endpoint requires a signed-in `TRAVELER` capability, matching the existing
role hierarchy in which managers and administrators include traveler permissions.

## Travel Aggregate

The travel root node contains:

- `id`: immutable UUID;
- `managerId`: immutable owner UUID;
- `name`: required display title;
- `description`: bounded descriptive text;
- `startDate` and `endDate`: inclusive local calendar dates;
- `capacity`: positive integer;
- `price`: nonnegative decimal stored as a decimal string to avoid floating-point loss;
- `currency`: uppercase ISO-4217-style three-letter code;
- `status`: `DRAFT`, `PUBLISHED`, `UNPUBLISHED`, or `CANCELLED`;
- `version`: monotonically increasing aggregate version;
- `createdAt` and `updatedAt`: UTC instants.

Existing stops, activities, accommodations and transportation remain owned graph
components. Existing participation nodes are treated as reserved/occupied places for
Phase 2 compatibility; Phase 3 replaces direct participation mutation with the
subscription lifecycle.

`remainingCapacity` and `bookable` are response projections. A listing is bookable
when it is `PUBLISHED`, its start date is after the current date, and participant count
is below capacity.

## Legacy Data Policy

At startup, travel-service finds `Travel` nodes without `managerId`, removes the
`Travel` label, and applies `LegacyTravelNeedsReview`. Their owned itinerary data is
preserved for inspection, but normal travel queries cannot return them. This operation
is repeat-safe. New and updated travel nodes are constrained by application validation
and covered by tests proving ownerless records remain invisible.

No arbitrary manager backfill is permitted because it would fabricate ownership and
could expose another person's listing.

## Authorization and Visibility

- `TRAVEL_MANAGER` creates a listing for their verified token user ID.
- `ADMIN` creates a listing for an explicitly supplied active manager ID.
- A manager can list, read, update, publish, unpublish and delete only their listings.
- An administrator can perform those operations for any manager.
- A traveler can list and read only bookable published listings.
- A non-admin request for another manager's private or nonexistent listing returns
  `404`, avoiding an ownership-information leak.
- Normal create/update payloads never accept a replacement owner. Phase 2 does not add
  reassignment because the audit does not require it; a future audited admin operation
  may be introduced if a real ownership-transfer use case appears.
- Destination catalogue writes remain administrator-only. Signed-in users may read
  destination data needed for listing and browsing flows.

Ownership checks occur inside travel-service using the verified `AccessToken`, not
only in the gateway or UI.

## API Shape

### Listings

- `POST /api/travels` creates a draft. Managers implicitly own it; administrators send
  `managerId`.
- `GET /api/travels` returns an owner-scoped manager list, a global administrator list,
  or the traveler-visible bookable catalogue.
- `GET /api/travels/{travelId}` applies the same ownership/visibility rules.
- `PUT /api/travels/{travelId}` replaces editable listing and itinerary fields while
  preserving owner, lifecycle, version and participants.
- `DELETE /api/travels/{travelId}` deletes an authorized listing only when existing
  subscription/participation state permits deletion.
- `POST /api/travels/{travelId}/publication` publishes a ready listing.
- `DELETE /api/travels/{travelId}/publication` returns a listing to `UNPUBLISHED`.

Publication operations are idempotent. Repeating the requested state returns the
current representation without emitting a duplicate lifecycle event.

### Internal reads

- travel-service exposes an internal manager-statistics endpoint protected by
  `X-Internal-Service-Token`.
- user-service exposes an internal safe manager-summary endpoint protected by the same
  mechanism.

### Public manager page

- `GET /api/managers/{managerId}` is served by engagement-service.
- It returns safe name/member-since data, completed-travel count, recent rating list,
  average rating and upheld report count.
- In Phase 2 rating data is an empty list, average rating is absent, and report count is
  zero. These are truthful values, not fabricated fixtures.

## Validation and Lifecycle Rules

Draft creation requires a name, description, valid dates, positive capacity,
nonnegative price, supported currency syntax, and at least one destination stop.
Activities, stays and transportation may be empty but must be explicitly present.

All stop, activity, accommodation and transportation dates must fall within the travel
date range. Stop positions are unique and contiguous from zero. Accommodation and
transport ranges cannot be reversed.

Publishing additionally requires:

- a future start date;
- at least one destination stop;
- nonblank description;
- positive capacity;
- price and currency;
- participant count not greater than capacity;
- all itinerary components passing their bounds validation.

An update cannot reduce capacity below the current participant count. Once
participants exist, an update cannot move the start date earlier or remove itinerary
dates in a way that excludes existing confirmed participation. Phase 3 will replace
this compatibility rule with subscription-state-aware validation and notification.

## Versioning and Events

Every successful aggregate mutation increments `version`. In the same Neo4j
transaction, travel-service creates one `TravelEvent` node containing:

- `eventId`;
- `eventType`;
- `aggregateId`;
- `aggregateVersion`;
- `occurredAt`;
- `traceId` when available;
- a versioned JSON payload containing the complete search/analytics projection.

Event types are `TRAVEL_CREATED`, `TRAVEL_UPDATED`, `TRAVEL_PUBLISHED`,
`TRAVEL_UNPUBLISHED`, and `TRAVEL_DELETED`. A uniqueness constraint on `eventId` and a
unique `(aggregateId, aggregateVersion)` constraint prevent duplicates. Event delivery
and cross-store reconciliation are completed in Phase 10; Phase 2 guarantees durable,
ordered change markers at the source transaction.

## Errors

The service uses stable safe codes:

- `MANAGER_REQUIRED` or `MANAGER_NOT_ACTIVE` for invalid administrative ownership;
- `INVALID_DATES`, `INVALID_CAPACITY`, `INVALID_PRICE`, and `INVALID_CURRENCY`;
- `DESTINATION_REQUIRED`, `INVALID_STOP_ORDER`, and itinerary bound codes;
- `PUBLICATION_NOT_READY` with field errors for incomplete publication;
- `CAPACITY_BELOW_PARTICIPANTS` for unsafe reductions;
- `TRAVEL_NOT_EDITABLE` or `TRAVEL_IN_USE` for lifecycle conflicts;
- `TRAVEL_STORE_UNAVAILABLE` and `DEPENDENCY_UNAVAILABLE` for safe 503 responses.

Authentication failures remain `401`, insufficient role remains `403`, and invisible
owner-scoped resources use `404`.

## Testing Strategy

Implementation follows test-driven development. Tests cover:

- domain validation for dates, itinerary bounds, capacity, price and currency;
- immutable ownership and administrator creation for another manager;
- manager self-scope and cross-manager `404` behavior through direct-service HTTP;
- traveler visibility for draft, unpublished, published, past and full listings;
- publication readiness and idempotent publish/unpublish behavior;
- capacity reductions with existing participants;
- Neo4j persistence, legacy quarantine, version increments, event atomicity and event
  uniqueness;
- concurrent updates and participant preservation;
- safe internal manager/profile endpoints and engagement-service composition;
- gateway routing and role matrix coverage for every new endpoint;
- clean Maven verification, UI regression checks, OpenAPI lint and Compose resolution.

## Completion Criteria

Phase 2 is complete only when:

1. two manager identities independently create and manage listings;
2. each manager receives `404` for the other's private listing and cannot mutate it;
3. an administrator can manage both managers' listings;
4. a traveler sees only future, published offerings with remaining capacity;
5. public manager pages expose only safe information and accurate Phase 2 statistics;
6. every successful listing mutation produces one ordered durable event marker;
7. all focused and repository-wide verification commands pass; and
8. the Phase 2 checklist and evidence are updated without marking later-phase behavior
   complete.
