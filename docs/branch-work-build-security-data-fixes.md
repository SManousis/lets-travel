# Work on `build-security-data-fixes`

- Recorded: 2026-09-30
- Base: `e01053e` (`main`)
- Implementation covered: through the current branch tip
- Integration status: pushed to Gitea; not merged into `main`.

This branch addresses findings from the [2026-09-29 project audit](audit/project-audit-2026-09-29.md). The audit's original findings describe the baseline at `e01053e`; its 2026-09-30 follow-ups describe the branch changes.

## Implemented

| Area | Work completed | Key commits |
|---|---|---|
| Build and configuration | Repaired the PowerShell Maven Wrapper path handling, the duplicate user-service security configuration, and the resulting startup wiring failure. | `bd827e8` |
| Authentication and identity | Persisted issued and revoked sessions and provisioned identities in the infrastructure profile. Added protected token validation used by the gateway and business services. Synchronized user email, role, and enabled-state changes with auth and revoked affected sessions. Made identity updates and session revocation atomic within the auth database. | `03a8cc0`, `bfc0f65`, `30ca278`, `66d58fa` |
| Travel data | Made Neo4j the infrastructure-profile source of truth for destinations, complete itineraries, and participants. Added graph transactions and cleanup of travel-owned nodes while preserving shared destinations. Documented handling for incomplete legacy travel records. | `eb8dc1e`, `6e235ba` |
| Payment data and providers | Added PostgreSQL-backed gateway and payment-method metadata in the infrastructure profile. Stripe and PayPal adapters now inspect supplied sandbox references and perform provider cleanup calls. Added pending, active, failed, and deletion-pending state handling, safe failure codes, idempotency, and concurrency controls. | `aab107c`, `a9cf6da`, `5e4f802`, `f2d3433` |
| Cross-service user deletion | Persisted user-deletion progress and coordinated auth revocation, payment-method cleanup, travel participation cleanup, and traveler deletion. Added tombstones to prevent new connections or participation after cleanup begins. Pending provider work returns `202` instead of reporting completion. | `02a0b2c` |
| Admin dashboard | Replaced the summary screen with user/traveler, destination/travel/itinerary, gateway, and payment-method workflows. Added a shared API client, login/logout and expiry handling, responsive forms, status/error messages, and retry-safe payment actions. | `52653a1` |
| CI and release path | Added enforced UI lint and explicit Jenkins checks, main-branch Sonar analysis/gate, SHA-tagged image publication, a release Compose overlay, secure dashboard/API routing, and an Ansible playbook that pulls published images with required secrets and TLS inputs. Source verification is recorded below; live Jenkins and deployment proof remains open. | 2026-09-30 follow-up |
| Local runtime fixes | Corrected fresh PostgreSQL role/database initialization, passed database passwords to the JDBC services, enabled Flyway startup, and fixed PostgreSQL timestamp binding for user/traveler creation. Added a focused regression test and storage-error logging. | 2026-09-30 runtime follow-up |

The design and implementation plans for these changes are in [`docs/superpowers`](superpowers/). The dashboard uses the **implemented controller payloads**; some planned OpenAPI fields and operations are not yet available.

## Verification recorded so far

- Earlier in this branch, `mvnw.cmd -B -ntp verify` passed with 57 tests after the auth work and 61 tests after the travel work. These runs **predate** the later payment, deletion, and dashboard changes; they are not a full-suite result for the branch tip.
- Base, secure, and scale Compose configuration checks passed after the auth and travel changes. This confirms manifest resolution, not a running deployment.
- The payment and cross-service deletion changes have code and compile evidence only. No credentialed Stripe/PayPal sandbox run or deployed PostgreSQL/Neo4j lifecycle run has been recorded.
- `npm --prefix admin-ui run build` passed for the dashboard at `52653a1`, including TypeScript checking and a production Vite build. No Chrome/Firefox walkthrough or UI test run has been recorded for that change.
- On 2026-09-30, full Maven verification (all six modules), UI lint/build, API contract lint, and release+secure Compose configuration passed locally. The Compose projection showed all six application images using the selected SHA tag and no application `build` contexts. These checks do not establish a running release. The Ansible CLI and configured Jenkins/Sonar/registry/deployment targets are unavailable locally.

Exact results and limitations are in the [audit follow-ups](audit/project-audit-2026-09-29.md). Migration and legacy-data procedures are in [operations](operations.md).

A later [isolated runtime verification](audit/runtime-verification-2026-09-30.md) passed the complete 62-test Maven reactor, UI/API lint and build, npm audits, Compose configuration, Ansible syntax/lint, API lifecycle with persistence and cleanup, and a limited dashboard browser smoke. It also found stale gateway routing after backend-only restarts and missing downstream request-ID logs, which prompted the next follow-up.

The [2026-10-01 runtime follow-up](audit/runtime-verification-2026-10-01.md) fixed the gateway's routed and auth-validation DNS refresh and confirmed a backend replacement with changed container IPs while the gateway stayed up. Request IDs now reach gateway, auth, and user logs, including controlled denial logging. Failed user inserts attempt to disable the provisioned auth subject and do not reserve the local email cache. The complete Maven reactor passed 73 tests. Durable cross-database creation/reconciliation, multi-replica recovery, and live external integrations remain open.

An opt-in [local payment simulator](payments.md#local-simulation-without-provider-accounts) now generates ignored synthetic credentials and exposes fake Stripe/PayPal references for API and dashboard rehearsal. The adapter, mock-provider, and gateway-to-payment flows passed local tests; the complete Maven reactor passed 76 tests after this addition. Real provider sandbox calls and customer ownership remain unverified.

The [2026-10-01 essentials check](audit/essentials-2026-10-01.md) records the next branch changes: dashboard unit tests and JUnit publication, npm advisory and Ansible checks in Jenkins, Debian/Ubuntu Docker host preparation, payment-method defaults, per-process log IDs, and fail-fast persisted-user loading. Local backend, dashboard, API, simulator, npm advisory, Ansible, Compose, and PostgreSQL migration checks passed. Live Jenkins and deployment evidence is still open.

## Remaining before the exercise audit

1. Configure Jenkins, SonarQube, a CI agent, and Gitea branch protection; demonstrate that failed PR checks block merging. Dashboard tests, npm advisory checks, Ansible checks, and main-only Sonar stages are wired in source, but no live PR gate or quality report has been recorded.
2. Configure a registry and Linux target; run the SHA-tagged image release and secure Ansible deployment, then record clean, repeat, and rollback results. The Debian/Ubuntu preparation playbook has syntax/lint evidence only. Vault initialization and app-side secret retrieval remain open.
3. Finish the remaining payment lifecycle/API decisions and verify Stripe and PayPal against sandboxes. Credentials and a trusted provider-customer-to-local-user mapping are not available yet, so provider ownership proof remains deferred.
4. Run and record deployed user-deletion/retry, authorization/TLS, database persistence, collector tracing, load/failover, and Chrome/Firefox dashboard scenarios. The local gateway-to-service trace and backend replacement probes are recorded, while the [browser checklist](audit/browser-checks.md) remains pending.
5. Reconcile checked phase claims and older architecture/API documentation with the implementation and dated evidence. Phase 7 payment completion and final audit readiness are not established.

Optional documentation, expanded test-suite, and Kubernetes bonuses in `plan.md` are separate from the mandatory gaps above.
