# Phase 3 verification — subscriptions, subscriber management, cancellation policy

Verified on 2026-10-07 on branch `phase-3-4` (base commit `675af88` plus the uncommitted Phase 3
changes) on macOS with OpenJDK 21.0.12, Node 24.21.0, npm 11.19.0 and the Maven Wrapper.
Design and reservation strategy: [subscriptions](../subscriptions.md).

## Automated checks

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp clean verify` | PASS. All nine Maven modules, 240 tests, zero failures and zero errors |
| `npm run lint:api` | PASS (exit 0) |
| `npm --prefix admin-ui run test:ci` | PASS. 4 files, 15 tests |
| `npm --prefix admin-ui run lint` | PASS (exit 0) |
| `npm --prefix admin-ui run build` | PASS (exit 0) |
| `docker compose -f infra/compose/compose.yml -f infra/compose/compose.ci.yml config --quiet` | PASS (exit 0); existing engagement wiring (`TRAVEL_SERVICE_URL`, `USER_SERVICE_URL`, PostgreSQL) already covers Phase 3 |

## What the tests prove

- `CancellationPolicyTest`: 4 days before allowed; exactly 3 days before allowed through
  23:59:59 local; less than 3 days and the start date rejected; the same instant is allowed in
  UTC/Los Angeles and rejected in Athens/Tokyo; DST changes on the cutoff day (Athens fall back,
  New York spring forward) do not move the boundary.
- `SubscriptionApiTest` (HTTP, H2 in PostgreSQL mode with the Flyway migrations, stand-in
  travel/user services):
  - subscribe creates `PENDING_PAYMENT` with the price/date/zone/cutoff snapshot, `Location`,
    an outbox `SubscriptionReserved` event without personal data; same key returns the same
    subscription; key reuse for another travel, duplicate active subscription, missing key and
    missing body are rejected;
  - unknown, full and unbookable travels and inactive travelers are rejected, with no place taken;
  - 10 concurrent subscribers for 2 places get exactly 2 × `201` and 8 × `TRAVEL_FULL`; 6
    concurrent requests from one traveler leave one active subscription and one place; 6
    concurrent retries with one key all return the same subscription;
  - traveler cancellation through the API at 4 days, exactly 3 days (23:59 local), 00:00:30 local
    the next day (still the cutoff date in UTC), the start date, a west-of-UTC zone, and repeats;
  - a manager lists only their own travel's subscribers (other manager `404`, traveler `403`,
    admin allowed), sees no contact data, removes with a required reason after the traveler
    cutoff, and the reason and actor land in `subscription_history`;
  - the internal payment outcome confirms or fails (place released), is retry-safe, and requires
    the service token;
  - a place release that fails while travel-service is down is finished by the retry job;
  - confirmed subscriptions become `COMPLETED` after the end date in the travel's zone and count
    as completed trips.
- `TravelReservationTest` (in-process Neo4j): 12 concurrent reservations for 3 places give
  exactly 3 places; reservation is idempotent per traveler and releasable; bookability uses the
  travel's own calendar; dates/price are fixed once places are taken.
- `TravelReservationApiTest`: the internal reserve/summary/release endpoints, `TRAVEL_FULL`,
  validation and the service-token requirement.
- `AuthorizationMatrixTest` (engagement, travel, user) lists every new endpoint;
  `GatewayRoleMatrixTest` and `GatewayRoutingTest` cover `/api/subscriptions/**` (TRAVELER),
  `/api/managed-travels/**` (TRAVEL_MANAGER) and confirm the new `/internal/**` paths are not routed.
- `Subscriptions.test.tsx`: subscribe reuses one Idempotency-Key across a failed retry; the cutoff
  is shown; cancel before the cutoff and "cancellation closed" after it; manager profile view and
  unsubscribe with a required reason.

## Not covered here

- The full Compose stack was not started for this phase; the checks above are automated.
- Payment confirmation, reservation expiry (30 minutes) and refunds are Phase 4; the internal
  `payment-outcome` endpoint is ready for payment-service to call.
- Outbox rows are written but not yet published to consumers (event pipeline work).
