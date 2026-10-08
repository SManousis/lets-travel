# Local runtime follow-up — 2026-10-01

Environment: Windows host, branch `build-security-data-fixes`. Verification used the separate Compose project `travel-plan-audit-20261001`, synthetic credentials, and loopback API port 18080. The existing Compose project was not used. The test project and volumes were removed after the run.

## Checks and results

| Check | Result | Evidence and limit |
|---|---|---|
| Maven reactor | Pass | `mvnw.cmd -B -ntp verify` built all six modules with 73 tests and zero failures/errors/skips. |
| Isolated Compose startup | Pass | PostgreSQL, Neo4j, four backend services, and the gateway reached healthy state. The dashboard was not started in this follow-up. |
| Gateway request ID | Pass | Authenticated `GET /api/users` returned 200 and the supplied `audit-final-valid-20261001` ID. Gateway, auth-service token validation, and user-service each emitted one JSON log line containing the same ID. An invalid bearer token returned 401 with its supplied request ID in the response and gateway log. The inspected trace lines contained no bearer token, password, or access token. |
| Backend replacement while gateway remains up | Pass in this isolated single-replica probe | After warming the route, replacing auth, user, travel, and payment containers changed the auth-service IP from `172.20.0.6` to `172.20.0.4` and the user-service IP from `172.20.0.7` to `172.20.0.5`. Gateway container ID `edc5b932cdfd3295038502c6c7b416c8f88941c42637992d4fede446ee60f3bd` stayed unchanged. The first authenticated user read after all backend health checks returned 200. |
| Failed user insert compensation | Pass in focused tests | A failed JDBC insert calls auth subject revocation and does not populate the local user cache. If revocation fails, the original database exception remains the primary error and the revocation failure is attached. No live failure injection was attempted. |

The first gateway DNS patch covered only routed requests. During the first backend replacement, login succeeded, but authenticated user reads returned 503 for all 15 attempts over 30 seconds because gateway token validation used a separate HTTP client. The follow-up patch gave that client the same five-second DNS cache limit. The successful replacement result above was recorded after that correction. A focused test now asserts the resolver configuration of both HTTP clients.

## Remaining limits

- The replacement probe covered one Compose host and one instance per service, after backend health checks. It does not prove uninterrupted requests during replacement, multi-replica load balancing, or database failover. Phase 11 remains open.
- User creation still spans auth and users databases without a durable operation record. Disabling a provisioned identity after a failed insert reduces exposure but is a best-effort compensation. A failed compensation, uncertain database commit, or retry conflict needs the [reconciliation procedure](../operations.md#reconciling-failed-user-creation).
- Stripe/PayPal sandbox calls and customer ownership, live Jenkins/Sonar/registry/deployment, TLS/Vault, and the full browser/responsive matrix still lack runtime evidence. This follow-up does not close those audit findings.

## Local payment simulation — 2026-10-01

An opt-in local provider simulator was added after the checks above. It generated synthetic credentials in the Git-ignored `.env.payment-sim` file and served two fake payment references on a private Compose network. The full Maven reactor passed 76 tests, including three adapter tests against a local HTTP provider and for credential guarding; `npm run test:payment-sim` passed three Node tests for the mock provider and credential generator. Compose configuration resolved, and an isolated stack reached healthy state. Through the gateway, the smoke script created a user and two gateways, connected both fake references to `ACTIVE` methods with safe display metadata, then deleted both methods with 204 responses. A second run retained two `ACTIVE` methods, one per provider; the dashboard returned HTTP 200. The isolated demo stack remains available on loopback API port 18080 and dashboard port 15173. No Stripe or PayPal endpoint was called in that run. The fixture customer IDs are synthetic and do not establish local-user ownership. Instructions are in [payments](../payments.md#local-simulation-without-provider-accounts).
