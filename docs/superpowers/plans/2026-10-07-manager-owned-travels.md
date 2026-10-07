# Manager-Owned Travel Offerings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Phase 2 with manager-owned, publishable travel offerings and a safe public manager page.

**Architecture:** Travel-service remains the Neo4j source of truth and stores an ordered event marker in the same transaction as each listing mutation. User-service supplies an internal safe manager summary, while engagement-service composes that summary with internal travel statistics for the public manager page.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security, Neo4j Java Driver 6, PostgreSQL/Flyway, Maven, JUnit 5, AssertJ, OpenAPI 3.0, Spring Cloud Gateway.

**Spec:** `docs/superpowers/specs/2026-10-07-manager-owned-travels-design.md`

## Global Constraints

- Roles remain `ADMIN`, `TRAVEL_MANAGER`, and `TRAVELER`, with the existing inheritance order.
- `managerId` is immutable through normal listing APIs.
- Ownerless Neo4j travels are quarantined as `LegacyTravelNeedsReview`, never assigned automatically.
- Money uses `BigDecimal` at API/domain boundaries and a plain decimal string in Neo4j.
- Currency is an uppercase three-letter code.
- Publication is idempotent and allowed only for future, complete listings.
- Non-admin callers receive `404` for another manager's private listing.
- Ratings and reports on the Phase 2 manager page are truthfully empty/zero until Phase 6.
- Every successful travel mutation writes one ordered `TravelEvent` marker atomically.
- Later-phase subscription, payment, feedback, recommendation and UI journeys stay out of scope.

## Review Focus

- An administrator that omits `managerId` or targets an inactive/non-manager identity receives a safe validation/conflict response; Task 3 tests this.
- Publication around the current local date is deterministic through an injected `Clock`; Task 1 tests future/today/past boundaries.
- Concurrent participant and listing updates cannot reduce capacity below occupancy or lose participants; Task 2 tests this against Neo4j.
- Decimal prices round-trip without binary floating-point changes and malformed currency never reaches persistence; Tasks 1 and 2 test this.
- A user-service or travel-service outage produces a safe `503` manager-page response with no partial/private data; Task 5 tests this.

---

### Task 1: Complete Travel Domain and Validation

**Files:**
- Modify: `lets-travel/src/main/java/com/travelplan/travel/TravelDomain.java`
- Modify: `lets-travel/src/main/java/com/travelplan/travel/TravelStore.java`
- Create: `lets-travel/src/main/java/com/travelplan/travel/TravelPolicy.java`
- Modify: `lets-travel/src/test/java/com/travelplan/travel/TravelStoreTest.java`
- Create: `lets-travel/src/test/java/com/travelplan/travel/TravelPolicyTest.java`

**Interfaces:**
- Produces: `TravelDomain.Status { DRAFT, PUBLISHED, UNPUBLISHED, CANCELLED }`.
- Produces: `TravelDomain.Travel` with `managerId`, `description`, `capacity`, `BigDecimal price`, `currency`, `status`, `version`, `createdAt`, and `updatedAt` in addition to the existing itinerary and participant fields.
- Produces: `TravelPolicy(Clock)` with `validateDraft(Travel)`, `validateForPublication(Travel)`, `bookable(Travel)`, and `remainingCapacity(Travel)`.
- Produces: `TravelStore.create(...)`, `update(...)`, `publish(...)`, and `unpublish(...)` returning complete immutable aggregates.

- [ ] **Step 1: Write failing domain-policy tests**

Add tests named `rejectsInvalidCapacityPriceCurrencyAndDates`, `requiresFutureCompleteTravelForPublication`, `computesBookabilityAtTodayBoundary`, and `preservesManagerLifecycleAndParticipantsOnUpdate`. Assert exact conflict codes and use a fixed `Clock` at `2026-10-07T00:00:00Z`.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `./mvnw -B -ntp -pl lets-travel -am -Dtest=TravelStoreTest,TravelPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation/test failures because the new fields and policy do not exist.

- [ ] **Step 3: Implement the expanded immutable domain**

Add the exact fields and status enum above. Keep defensive copies for itinerary lists and participant IDs. Format currency with `Locale.ROOT`; validate price using `BigDecimal.signum()`.

- [ ] **Step 4: Implement draft, publication and bookability policy**

Draft validation enforces dates, capacity, price, currency and existing itinerary bounds. Publication additionally requires `startDate.isAfter(LocalDate.now(clock))`, nonblank description and occupancy not greater than capacity.

- [ ] **Step 5: Update the in-memory store path**

Create drafts at version `1`; increment version and `updatedAt` on effective mutations; preserve `managerId`, lifecycle and participants on normal update; make repeated publish/unpublish calls no-ops.

- [ ] **Step 6: Run focused tests**

Run the Task 1 command again.

Expected: all focused tests pass.

- [ ] **Step 7: Commit**

```bash
git add lets-travel/src/main lets-travel/src/test
git commit -m "feat(travel): add complete manager-owned travel domain"
```

### Task 2: Persist Ownership, Lifecycle and Events in Neo4j

**Files:**
- Modify: `lets-travel/src/main/java/com/travelplan/travel/Neo4jTravelRepository.java`
- Modify: `infra/neo4j/init/001-constraints.cypher`
- Modify: `lets-travel/src/test/java/com/travelplan/travel/Neo4jTravelRepositoryTest.java`

**Interfaces:**
- Consumes: the expanded `TravelDomain.Travel` and policy/store mutations from Task 1.
- Produces: `Neo4jTravelRepository.list(UUID managerId, boolean publishedOnly)` and aggregate persistence for every new field.
- Produces: atomic `TravelEvent` nodes with `(eventId, eventType, aggregateId, aggregateVersion, occurredAt, traceId, payload)`.
- Produces: repeat-safe `quarantineOwnerlessTravels()` invoked during schema initialization.

- [ ] **Step 1: Write failing Neo4j tests**

Add tests named `roundTripsManagerLifecycleCapacityAndDecimalPrice`, `quarantinesOwnerlessLegacyTravels`, `writesOneOrderedEventPerEffectiveMutation`, `doesNotEmitForIdempotentPublication`, and `concurrentUpdatePreservesParticipantsAndCapacityInvariant`.

- [ ] **Step 2: Run the Neo4j integration test and confirm failure**

Run: `./mvnw -B -ntp -pl lets-travel -am -Dtest=Neo4jTravelRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: failures for absent properties, constraints and event nodes.

- [ ] **Step 3: Add constraints and legacy quarantine**

Add a unique `TravelEvent.id` constraint, a unique `(aggregateId, aggregateVersion)` constraint, a `Travel.managerId` index, and the repeat-safe label migration for ownerless nodes in both startup initialization and the checked-in Cypher script.

- [ ] **Step 4: Persist and hydrate every aggregate field**

Use parameterized Cypher only. Store price through `toPlainString()`, status through `name()`, and timestamps as ISO-8601 strings. Filter normal reads to `Travel` nodes; quarantined nodes have that label removed.

- [ ] **Step 5: Write event markers in mutation transactions**

Create exactly one marker after the aggregate mutation succeeds. Serialize a stable JSON projection with Spring's existing JSON support or a small deterministic encoder; deleted events retain the last projection needed by consumers.

- [ ] **Step 6: Preserve concurrency invariants**

Lock the travel root before reading occupancy, update version once, reject `capacity < participantCount`, and hydrate participants after the lock so a concurrent participant commit is not lost.

- [ ] **Step 7: Run the focused Neo4j test**

Run the Task 2 command again.

Expected: all Neo4j integration tests pass.

- [ ] **Step 8: Commit**

```bash
git add lets-travel/src/main lets-travel/src/test infra/neo4j/init/001-constraints.cypher
git commit -m "feat(travel): persist ownership lifecycle and events"
```

### Task 3: Validate Manager Identities Internally

**Files:**
- Modify: `user-service/src/main/java/com/travelplan/users/UserInternalController.java`
- Create: `user-service/src/test/java/com/travelplan/users/ManagerInternalApiTest.java`
- Create: `lets-travel/src/main/java/com/travelplan/travel/ManagerIdentityClient.java`
- Create: `lets-travel/src/test/java/com/travelplan/travel/ManagerIdentityClientTest.java`
- Modify: `lets-travel/src/main/resources/application.yml`

**Interfaces:**
- Produces: `GET /internal/managers/{id}` returning `ManagerSummary(UUID managerId, String displayName, Instant memberSince)` only for active `TRAVEL_MANAGER` identities; admins are not manager owners.
- Produces: `ManagerIdentityClient.requireActiveManager(UUID)` returning the same safe summary and mapping 404/409 to `MANAGER_NOT_ACTIVE`, dependency failures to `DEPENDENCY_UNAVAILABLE`.

- [ ] **Step 1: Write failing internal API tests**

Test valid manager, traveler, admin, disabled manager, missing identity, absent token, wrong token, and ensure email/private profile fields never appear in JSON.

- [ ] **Step 2: Run user-service focused tests and confirm failure**

Run: `./mvnw -B -ntp -pl user-service -am -Dtest=ManagerInternalApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 404/no mapping for the new endpoint.

- [ ] **Step 3: Implement the safe user-service endpoint**

Reuse constant-time internal-token comparison. Build display name from first and last name and return only the exact safe summary fields.

- [ ] **Step 4: Write failing travel-service client tests**

Use a local HTTP stub to assert the internal token and request ID are forwarded and every upstream status maps to the specified result/code.

- [ ] **Step 5: Implement `ManagerIdentityClient`**

Use Java `HttpClient` with bounded timeout and the configured `services.users.url` plus `security.internal-service-token`.

- [ ] **Step 6: Run both focused test classes**

Run: `./mvnw -B -ntp -pl user-service,lets-travel -am -Dtest=ManagerInternalApiTest,ManagerIdentityClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all focused tests pass.

- [ ] **Step 7: Commit**

```bash
git add user-service lets-travel
git commit -m "feat(users): expose safe manager identity validation"
```

### Task 4: Add Role-Scoped Travel APIs

**Files:**
- Modify: `lets-travel/src/main/java/com/travelplan/travel/TravelController.java`
- Modify: `lets-travel/src/main/java/com/travelplan/travel/security/SecurityConfig.java`
- Modify: `lets-travel/src/test/java/com/travelplan/travel/AuthorizationMatrixTest.java`
- Create: `lets-travel/src/test/java/com/travelplan/travel/ManagerTravelOwnershipTest.java`
- Create: `lets-travel/src/test/java/com/travelplan/travel/TravelerPublishedVisibilityTest.java`

**Interfaces:**
- Consumes: Task 1 store/policy and Task 3 manager client.
- Produces: canonical `POST/GET /api/travels`, `GET/PUT/DELETE /api/travels/{id}`, and `POST/DELETE /api/travels/{id}/publication` endpoints.
- Produces: `GET /internal/managers/{managerId}/travel-statistics` returning completed count and safe listing totals.

- [ ] **Step 1: Write failing authorization and ownership tests**

Cover manager self CRUD, cross-manager `404`, admin global CRUD, admin missing/invalid `managerId`, traveler write `403`, direct-service calls, and all new mappings in the matrix.

- [ ] **Step 2: Write failing published-visibility tests**

Create draft, published future, published today, past, full and unpublished fixtures. Assert travelers list/read only the future published non-full fixture, while owners/admins retain authorized visibility.

- [ ] **Step 3: Run focused HTTP tests and confirm failure**

Run: `./mvnw -B -ntp -pl lets-travel -am -Dtest=AuthorizationMatrixTest,ManagerTravelOwnershipTest,TravelerPublishedVisibilityTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: failures because endpoints remain admin-only and unscoped.

- [ ] **Step 4: Implement controller DTOs and ownership rules**

Inject `AccessToken` with `@AuthenticationPrincipal`. Managers derive owner from the token; admins require body `managerId`. Use owner-aware store reads and `404` for invisible records. Keep error bodies free of private details.

- [ ] **Step 5: Implement publication and internal statistics endpoints**

Publication endpoints use the policy and idempotency behavior from Task 1. Protect internal statistics with constant-time internal-token validation.

- [ ] **Step 6: Update service security rules**

Allow signed-in traveler capability to read destinations/travels and the manager page data needed downstream; require controller-level capability/ownership for mutations; keep destination mutations admin-only and `/internal/**` gateway-inaccessible.

- [ ] **Step 7: Run all travel-service tests**

Run: `./mvnw -B -ntp -pl lets-travel -am test`

Expected: all travel and dependency-module tests pass.

- [ ] **Step 8: Commit**

```bash
git add lets-travel
git commit -m "feat(travel): enforce manager ownership and publication"
```

### Task 5: Implement the Public Manager Page

**Files:**
- Create: `engagement-service/src/main/java/com/travelplan/engagement/ManagerPageController.java`
- Create: `engagement-service/src/main/java/com/travelplan/engagement/ManagerPageService.java`
- Create: `engagement-service/src/main/java/com/travelplan/engagement/UserManagerClient.java`
- Create: `engagement-service/src/main/java/com/travelplan/engagement/TravelManagerClient.java`
- Modify: `engagement-service/src/main/resources/application.yml`
- Modify: `engagement-service/src/test/java/com/travelplan/engagement/AuthorizationMatrixTest.java`
- Create: `engagement-service/src/test/java/com/travelplan/engagement/ManagerPageTest.java`

**Interfaces:**
- Consumes: Task 3 manager summary and Task 4 manager travel statistics.
- Produces: `GET /api/managers/{managerId}` returning `ManagerPage(managerId, displayName, memberSince, completedTravels, totalTravels, averageRating, recentRatings, reportCount)`.

- [ ] **Step 1: Write failing manager-page tests**

Test safe composition, all three signed-in roles, anonymous `401`, missing manager `404`, wrong internal responses, user outage `503`, travel outage `503`, and absence of email/private report content.

- [ ] **Step 2: Run focused engagement tests and confirm failure**

Run: `./mvnw -B -ntp -pl engagement-service -am -Dtest=ManagerPageTest,AuthorizationMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: no manager-page mapping exists.

- [ ] **Step 3: Implement bounded internal clients**

Use Java `HttpClient`, configured service URLs, the internal token and propagated `X-Request-ID`. Do not return partial data when either source is unavailable.

- [ ] **Step 4: Implement service and controller**

Compose the two safe responses. Return `averageRating: null`, `recentRatings: []`, and `reportCount: 0`. Map dependency failures to a safe `503 DEPENDENCY_UNAVAILABLE` response.

- [ ] **Step 5: Update authorization matrix and run engagement tests**

Run: `./mvnw -B -ntp -pl engagement-service -am test`

Expected: all engagement and dependency-module tests pass.

- [ ] **Step 6: Commit**

```bash
git add engagement-service
git commit -m "feat(engagement): add safe public manager pages"
```

### Task 6: Route and Document Phase 2 APIs

**Files:**
- Modify: `api-gateway/src/main/resources/application.yml`
- Modify: `api-gateway/src/test/java/com/travelplan/gateway/GatewayRoleMatrixTest.java`
- Modify: `api-gateway/src/test/java/com/travelplan/gateway/GatewayRoutingTest.java`
- Modify: `docs/api/travels.yaml`
- Modify: `docs/api/dashboards.yaml`
- Modify: `docs/architecture.md`
- Modify: `docs/data-model.md`
- Modify: `infra/compose/compose.yml`
- Modify: `infra/compose/compose.ci.yml`

**Interfaces:**
- Consumes: public and internal endpoints from Tasks 3–5.
- Produces: gateway route `/api/managers/** -> engagement-service` with minimum role `TRAVELER`.
- Produces: OpenAPI schemas matching the implemented field names, lifecycle operations, visibility rules and safe error codes.

- [ ] **Step 1: Write failing gateway routing and role assertions**

Add engagement upstream configuration and assert `/api/managers/{id}` routes there for traveler, manager and admin tokens while anonymous callers receive `401`.

- [ ] **Step 2: Run gateway tests and confirm failure**

Run: `./mvnw -B -ntp -pl api-gateway -am test`

Expected: routing/matrix failures for the missing engagement route and changed travel role.

- [ ] **Step 3: Update gateway and Compose configuration**

Add `services.engagement.url`, the route, environment wiring and existing restart/health conventions. Change the gateway minimum travel read capability to `TRAVELER`; receiving-service ownership remains authoritative.

- [ ] **Step 4: Replace the legacy travel contract with Phase 2 schemas**

Document exact request/response fields, status enum, publication endpoints, role/ownership visibility, pagination and conflict codes. Keep manager-page response aligned with the engagement implementation.

- [ ] **Step 5: Update architecture and data-model documentation**

Document immutable ownership, legacy quarantine, lifecycle transitions, event nodes, internal calls and Phase 6 zero/empty manager-page fields.

- [ ] **Step 6: Run routing, API-lint and Compose checks**

Run: `./mvnw -B -ntp -pl api-gateway -am test`

Run: `npm run lint:api`

Run: `docker compose -f infra/compose/compose.yml -f infra/compose/compose.ci.yml config --quiet`

Expected: all commands exit `0`.

- [ ] **Step 7: Commit**

```bash
git add api-gateway docs infra/compose
git commit -m "docs(api): publish manager-owned travel contracts"
```

### Task 7: Complete Verification and Phase Evidence

**Files:**
- Modify: `lets-travel-plan.md`
- Create: `docs/audit/phase-2-verification-2026-10-07.md`
- Modify: `README.md`
- Modify: `Jenkinsfile`

**Interfaces:**
- Consumes: all Phase 2 behavior from Tasks 1–6.
- Produces: repeatable Phase 2 verification evidence and checked roadmap items.

- [ ] **Step 1: Add an audit-focused two-manager scenario**

Document commands and expected results for manager A/B creation, isolated listing CRUD, cross-owner `404`, admin override, traveler published visibility and safe manager page.

- [ ] **Step 2: Fix clean-build reproducibility commands**

Use `./mvnw -B -ntp clean verify` in README and Jenkins so removed compiled classes cannot survive incremental builds.

- [ ] **Step 3: Run the full backend verification**

Run: `./mvnw -B -ntp clean verify`

Expected: all nine Maven modules succeed with zero failures/errors.

- [ ] **Step 4: Run frontend and contract verification**

Run: `npm run lint:api`

Run: `npm --prefix admin-ui run test:ci`

Run: `npm --prefix admin-ui run lint`

Run: `npm --prefix admin-ui run build`

Expected: every command exits `0`.

- [ ] **Step 5: Run Compose resolution**

Run: `docker compose -f infra/compose/compose.yml -f infra/compose/compose.ci.yml config --quiet`

Expected: exit `0` with all required service URLs and health checks resolved.

- [ ] **Step 6: Update roadmap checkboxes and evidence**

Mark each Phase 2 item complete only when its implementation/test evidence is present. Mark the Phase 1 completion gate once the cross-manager authorization tests pass. Record commit SHA, commands, date and environment in the evidence file.

- [ ] **Step 7: Commit**

```bash
git add lets-travel-plan.md docs/audit README.md Jenkinsfile
git commit -m "docs: record phase 2 completion evidence"
```
