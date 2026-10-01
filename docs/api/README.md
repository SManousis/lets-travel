# Future admin API contracts

The OpenAPI 3.0.3 files in this directory describe planned APIs for Phases 4–7.
Phase 1 starts applications and configures routes; it does **not** implement these
endpoints, authentication, lifecycle workers, databases, or provider integrations.

| File | Gateway paths |
| --- | --- |
| [auth.yaml](auth.yaml) | `/api/auth` login, refresh, logout, current identity |
| [users.yaml](users.yaml) | `/api/users`, `/api/travelers`, lifecycle operation status/retry |
| [travels.yaml](travels.yaml) | `/api/travels`, nested itinerary/participation, `/api/destinations` |
| [payments.yaml](payments.yaml) | `/api/payment-methods`, `/api/payment-gateways`, provider operation status/retry |

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

An asynchronous operation's failure is retrieved with `200` and `status: FAILED`,
not an HTTP error for the successful status read. Replaying a DELETE with its
original idempotency key returns the original operation even after the target is
gone; a new request for a missing target returns `404`. Synchronous deletes return
`404` when repeated. References to inactive/deleting owners return `409`; unknown
references return `404`. Auth provisioning/internal cleanup wire APIs, provider
callbacks, and tokenization/approval routes will be specified when those features
are implemented. The payment create contract assumes an already obtained sandbox
provider reference, not raw payment credentials or a working connection flow.
