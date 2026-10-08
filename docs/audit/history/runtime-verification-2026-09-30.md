# Local runtime verification — 2026-09-30

Environment: Windows host, Docker Engine 29.6.1, branch `build-security-data-fixes`. The tests used a separate Compose project named `travel-plan-audit-20260930`, synthetic administrator and database credentials, and loopback ports 18080 (API) and 15173 (dashboard). The pre-existing `travel-plan` project was not changed. The isolated project and its test volumes were removed after the run.

## Results

| Check | Result | Evidence and limit |
|---|---|---|
| Maven reactor: `mvnw.cmd -B -ntp verify` | Pass | Six modules built; 62 tests, zero failures/errors/skips. |
| Dashboard: `npm --prefix admin-ui ci`, `run lint`, `run build` | Pass | Production TypeScript/Vite build completed. No UI unit-test script exists. |
| API contracts: root `npm ci`, `npm run lint:api` | Pass | Four OpenAPI documents linted. Host Node 24.18.0 is outside the root package's declared Node 22 range. |
| npm dependency audit, root and dashboard | Pass | `npm audit --audit-level=high` reported zero vulnerabilities in each project, including development dependencies. This is an npm-only snapshot, not a Maven or image scan. |
| Compose configuration | Pass | Base, secure, scale, and release plus secure overlays resolved with synthetic environment values and a 40-character test image tag. |
| Ansible playbook | Pass | In a temporary Linux Python container, `ansible-lint infra/ansible/deploy.yml` reported zero failures/warnings at the production profile; `ansible-playbook --syntax-check` passed. No target deployment was attempted. |
| Fresh isolated stack | Pass after fixes | PostgreSQL, Neo4j, four business/auth services, gateway, and dashboard reached healthy/running state. Fresh PostgreSQL initialization created three application roles/databases; Flyway applied auth V1–V3, user V1–V2, and payment migrations. |
| API lifecycle | Pass | Unauthenticated and tampered tokens returned 401; admin login, user/traveler/destination/complete-travel/gateway creation, persistence after all Java services and gateway restarted, cross-service user deletion, traveler participation cleanup, logout, and revoked-token denial succeeded. A completed user deletion retains a disabled user tombstone by design. |
| Dashboard browser smoke | Partial pass | In the Codex in-app browser, admin login, user create/edit, traveler create, travel detail and payment/gateway screens, sign out, and bad-password feedback worked against the isolated stack. The complete Chrome/Firefox and 1440/768/390px matrix in `browser-checks.md` remains unrun. |
| Request trace | Partial | A supplied request ID appeared once in gateway logs and zero times in user-service logs. The downstream trace requirement is still open. |

## Failures uncovered and corrected

1. Fresh PostgreSQL initialization left zero app roles/databases because `psql` variables inside a `DO` block were not expanded. The script now uses `SELECT format(...) ... \gexec` and `ON_ERROR_STOP=1`; a fresh-volume run created all three roles/databases.
2. The three JDBC services did not receive their database passwords from Compose. Passing each app's configured password allowed authenticated connections.
3. The PostgreSQL services packaged Flyway migrations but lacked Spring Boot's Flyway starter. Adding it to auth, user, and payment service POMs applied migrations on startup; admin login then succeeded.
4. User and traveler inserts bound `java.time.Instant` directly. PostgreSQL JDBC rejected it, causing user creation to return 503 after identity provisioning. Both inserts now bind `Timestamp`; a focused regression test failed before the fix and passed afterward. User storage exceptions now log their cause while the API keeps its controlled 503 response.
5. Ansible lint found one overlong YAML expression. Wrapping it preserved the condition; lint and syntax checks passed on rerun.

## Open findings and limits

- Restarting backend containers alone changed a user-service container IP while the still-running gateway kept routing to its previous address; authenticated reads returned 500 throughout the 90-second probe. Restarting the gateway with the services restored routing, and the full-stack restart persistence test passed. Rolling replacement/failover behavior remains unverified and needs a routing fix or recovery procedure before claiming Phase 11.
- The initial failed user insert left a provisioned auth identity with no user row. The timestamp fix removes that particular failure, but a future database error can still cause cross-database identity/profile divergence; durable compensation or reconciliation remains open.
- Gateway logs contain the supplied request ID, but the downstream user-service log does not. The Phase 9 trace rehearsal is incomplete.
- No Stripe or PayPal sandbox credentials or trusted provider-customer ownership mapping were available, so provider calls and ownership checks remain unverified.
- Jenkins, SonarQube quality gates, Gitea branch protection, registry publication, a prepared Linux deployment target, real TLS/Vault inputs, and replica load/failover remain untested here. The Ansible result establishes syntax/lint only.
- The browser smoke covered one in-app browser at its default width. UI validation/error/dependency cases and the Chrome/Firefox responsive matrix remain open.
