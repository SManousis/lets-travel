# API contracts

The OpenAPI 3.0.3 files in this directory are target contracts. A contract being
present does not mean its endpoints are implemented; each file's description
names the phase that implements it.

| File | Owner | Gateway paths |
| --- | --- | --- |
| [auth.yaml](auth.yaml) | auth-service | `/api/auth` login, refresh, logout, current identity |
| [users.yaml](users.yaml) | user-service | `/api/users`, `/api/travelers`, lifecycle operation status/retry |
| [travels.yaml](travels.yaml) | travel-service | `/api/travels`, nested itinerary/participation, `/api/destinations` |
| [recommendations.yaml](recommendations.yaml) | travel-service | `/api/recommendations` |
| [payments.yaml](payments.yaml) | payment-service | `/api/payment-methods`, `/api/payment-gateways`, provider operation status/retry |
| [engagement.yaml](engagement.yaml) | engagement-service | `/api/subscriptions`, `/api/managed-travels`, `/api/feedback`, `/api/reports`, `/api/admin/reports` |
| [dashboards.yaml](dashboards.yaml) | engagement-service | `/api/dashboards`, `/api/managers` |
| [search.yaml](search.yaml) | search-service | `/api/search` |

## Roles

Roles are `ADMIN`, `TRAVEL_MANAGER` and `TRAVELER`, and they inherit:
`ADMIN` > `TRAVEL_MANAGER` > `TRAVELER`. An operation's `x-required-role` (set
per operation, or for the whole file) is the *minimum* role, so a `TRAVELER`
operation is also open to managers and admins. A plain HTTP bearer scheme cannot
express roles, which is why the extension exists; the gateway and the receiving
service both enforce it.

Role checks are not enough on their own. Services also check ownership:

- A traveler acts only on their own subscriptions, feedback and reports.
- A travel manager manages only travels whose `managerId` is theirs, and only
  those travels' subscribers and feedback.
- An admin may act on any resource.

A request outside the caller's ownership returns `404`, not `403`, so it does not
reveal that another user's resource exists. The full capability matrix is in
[architecture](../architecture.md#role-capabilities).

The four Travel Plan contracts (auth, users, travels, payments) still declare
`x-required-role: ADMIN` everywhere; Phase 1 and Phase 2 open the relevant
operations to the new roles.

Each document is standalone with local component references. The local server is
`http://localhost:8080`; paths already contain `/api`, and the gateway must not
strip it. The deployed server uses HTTPS. Business operations require a bearer
token with `ADMIN`; a plain HTTP bearer security scheme cannot encode role checks,
so each contract additionally declares `x-required-role: ADMIN`. Login has no
authentication requirement. Refresh uses the secure refresh cookie plus CSRF
header, and logout requires both bearer and refresh-cookie/CSRF protection.
Tokens and secret-bearing request fields must never be logged.

Lists use zero-based `page` (default 0) and `size` (default 20, maximum 100). Results
contain `items`, `page`, `size`, `totalElements`, and `totalPages`, ordered by
`createdAt` ascending then UUID ascending. Empty pages return `200` with an empty
array; pagination is not a stable snapshot during concurrent mutations. Dates
use `YYYY-MM-DD`, timestamps use RFC 3339, and identifiers use UUID strings.

Create returns `201` plus `Location`, update/read returns `200`, and completed
deletion returns `204`. Durable cross-service/provider work returns `202` with
an operation body and `Location` pointing to its status endpoint. Supply the
required UUID `Idempotency-Key`; identical retries recover the original operation,
different payloads with the same key return `409`. Poll operation status, and use
its retry endpoint only for a retryable failed operation. Retention, cleanup
ordering, and deletion fences are specified in [architecture](../architecture.md).
PATCH schemas allow only specified mutable fields and require at least one field.
PUT replaces the complete travel/itinerary; omitted owned components are deleted.

All errors share `code`, safe `message`, `fieldErrors` (possibly empty), and
`traceId`; operation failures reuse this shape. Clients branch on `code`, never
message text. Standard codes include:

| Status | Meaning and representative code |
| --- | --- |
| 400 | Invalid fields, malformed UUID/date, or invalid pagination: `VALIDATION_ERROR` |
| 401 | Missing/invalid/expired/revoked credentials: `UNAUTHENTICATED` |
| 403 | Valid identity lacks `ADMIN`, or CSRF validation failed: `FORBIDDEN` |
| 404 | Requested resource does not exist: `NOT_FOUND` |
| 409 | Duplicate email/reference, in-use destination/gateway, protected admin, lifecycle/ownership conflict, or idempotency mismatch: `CONFLICT` or a more specific stable code |
| 503 | Owner cannot durably accept work, or a synchronous dependency is unavailable: `DEPENDENCY_UNAVAILABLE`; response may include `Retry-After` |

Let's Travel adds these stable business codes:

| Status | Code | Raised when |
| --- | --- | --- |
| 400 | `SELF_REPORT` | A user reports themselves |
| 409 | `TRAVEL_NOT_BOOKABLE` | Subscribing to an unpublished, started or past travel |
| 409 | `TRAVEL_FULL` | No place left |
| 409 | `ALREADY_SUBSCRIBED` | The caller already holds an active subscription |
| 409 | `CANCELLATION_CUTOFF_PASSED` | Traveler cancels later than start date minus 3 days |
| 409 | `FEEDBACK_NOT_ELIGIBLE` | No completed participation for that travel |
| 409 | `FEEDBACK_ALREADY_SUBMITTED` | Second feedback for the same participation |
| 409 | `DUPLICATE_REPORT` | The caller already has an open report on that target |
| 503 | `SEARCH_UNAVAILABLE` | The search index is down; use the published-travel browse |

Synchronous creates that must not be duplicated (subscribing, cancelling,
removing a subscriber, feedback, reports) also take a required UUID
`Idempotency-Key`, with the same replay and `409` rules as asynchronous operations.
Money is an integer `amountMinor` plus an ISO 4217 `currency`, never a float.

An asynchronous operation's failure is retrieved with `200` and `status: FAILED`,
not an HTTP error for the successful status read. Replaying a DELETE with its
original idempotency key returns the original operation even after the target is
gone; a new request for a missing target returns `404`. Synchronous deletes return
`404` when repeated. References to inactive/deleting owners return `409`; unknown
references return `404`. Auth provisioning/internal cleanup wire APIs, provider
callbacks, and tokenization/approval routes will be specified when those features
are implemented. The payment create contract assumes an already obtained sandbox
provider reference, not raw payment credentials or a working connection flow.
