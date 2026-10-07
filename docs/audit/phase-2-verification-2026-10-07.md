# Phase 2 verification — manager-owned travel offerings

Verified on 2026-10-07 on branch `phase-2` (base commit `a407a79` plus the
uncommitted Task 6 fixes and Task 7 changes) on macOS with OpenJDK 21.0.12,
Node 24.21.0, npm 11.19.0 and the Maven Wrapper.

## Automated checks

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp clean verify` | PASS. All nine Maven modules pass, 208 tests, zero failures and zero errors |
| `npm run lint:api` | PASS (exit 0) |
| `npm --prefix admin-ui run test:ci` | PASS (exit 0) |
| `npm --prefix admin-ui run lint` | PASS (exit 0) |
| `npm --prefix admin-ui run build` | PASS (exit 0) |
| `docker compose -f infra/compose/compose.yml -f infra/compose/compose.ci.yml config --quiet` | PASS (exit 0) |

Key test classes:

- `TravelPolicyTest` checks validation and the date boundaries for publication.
- `Neo4jTravelRepositoryTest` checks:
  - lifecycle persistence;
  - `TravelEvent` markers;
  - concurrent capacity and participant updates;
  - the legacy quarantine.
- `ManagerTravelOwnershipTest` checks:
  - that a manager can only act on their own listings;
  - that another manager gets `404`;
  - the admin override.
- `TravelerPublishedVisibilityTest` checks that travelers only see listings that are published and bookable.
- `ManagerInternalApiTest` and `ManagerIdentityClientTest` check the internal manager validation.
- `ManagerPageTest`, `ManagerPageOutageTest` and `ManagerPageUserOutageTest` check:
  - that the manager page is assembled safely;
  - that a dependency outage returns `503`;
  - that no private fields leak.
- `GatewayRoutingTest` and `GatewayRoleMatrixTest` check:
  - that `/api/managers/**` is routed to engagement-service for `TRAVELER` and above;
  - that destination reads need `TRAVELER`;
  - that destination writes need `ADMIN`;
  - that `/internal/**` is not routed.

## Runtime startup (Phase 0 completion gate)

`docker compose -p lets-travel-gate -f infra/compose/compose.yml up -d --build --wait` started all
11 containers healthy: admin-ui, api-gateway, auth-service, user-service, travel-service,
payment-service, engagement-service, search-service, postgres, neo4j and elasticsearch.

The smoke checks then gave these results:

- Through the gateway, `/api/travels` and `/api/managers/{id}` without a token returned `401`.
- `/internal/managers/x` returned `404`, so it is not routed.
- A bad login returned `401`.
- The admin UI returned `200`.

The stack was stopped afterwards with `down`.

## Two-manager audit scenario

Prerequisites: the stack is running (`docker compose -f infra/compose/compose.yml up -d`),
and the gateway is at `$GW`. As admin, create managers A and B, then finish password
setup for each of them. Sign in to get the tokens `$ADMIN`, `$A`, `$B` and `$T` (a traveler).

| Step | Request | Expected |
| --- | --- | --- |
| 1 | A: `POST $GW/api/travels` with a complete future listing | `201`, `managerId` = A, `status` = `DRAFT` |
| 2 | B: `POST $GW/api/travels` | `201`, `managerId` = B |
| 3 | A: `GET $GW/api/travels` | only A's listings |
| 4 | B: `GET`/`PUT`/`DELETE $GW/api/travels/{A's id}` | `404 NOT_FOUND`, with no data leaked |
| 5 | A: `PUT $GW/api/travels/{id}` with a different `managerId` | the owner is unchanged |
| 6 | Admin: `PUT $GW/api/travels/{B's id}` | `200`, admin override |
| 7 | T: `GET $GW/api/travels` before publication | the draft is not listed |
| 8 | A: `POST $GW/api/travels/{id}/publication` (twice) | `200` both times (idempotent) |
| 9 | T: `GET $GW/api/travels` | the published, bookable listing is visible |
| 10 | A: `DELETE $GW/api/travels/{id}/publication` | `UNPUBLISHED`; T no longer sees it |
| 11 | T: `GET $GW/api/managers/{A}` | `managerId`, `displayName`, `memberSince`, `completedTravels`, `totalTravels`, `averageRating: null`, `recentRatings: []`, `reportCount: 0`, and no email |
| 12 | Anonymous: `GET $GW/api/managers/{A}` | `401` |
| 13 | Stop `user-service`, then T: `GET $GW/api/managers/{A}` | `503 DEPENDENCY_UNAVAILABLE`, with no partial data |

## Not verified here

- The two-manager scenario was not run by hand against the stack. Its behavior is
  covered by the automated tests listed.
- Protecting confirmed subscriptions from invalidating edits depends on
  subscriptions, which arrive in Phase 3.
