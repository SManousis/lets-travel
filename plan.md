# Travel-Plan Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` to implement this plan phase by phase. Update the checkboxes after verifying the work. This document is a roadmap; creating it does not complete any implementation phase.

**Goal:** Build the infrastructure and secure Admin Dashboard required for part one of the Travel Management System, and demonstrate every mandatory audit criterion.

**Architecture:** Separate authentication, user management, travel management, and payment management into independently deployable Java services behind an API gateway. Each service owns its data and exposes authenticated APIs; a browser dashboard accesses them through the gateway. PostgreSQL stores identity, user, and payment records; Neo4j stores travels and their itinerary relationships.

**Proposed stack:** Java with Spring Boot, Maven Wrapper, Spring Security, Spring Cloud Gateway, PostgreSQL, Neo4j, React with TypeScript, Docker Compose, Ansible, Jenkins, SonarQube, HashiCorp Vault, and OpenTelemetry with a trace/log backend. These are implementation defaults, not additional assignment requirements. Record compatible supported versions and package justifications in Phase 1 before generating code.

**Sources:** [Exercise](https://github.com/01-edu/public/blob/master/subjects/java/projects/travel-plan/README.md) · [Audit](https://github.com/01-edu/public/blob/master/subjects/java/projects/travel-plan/audit/README.md). Reviewed on 2026-09-11.

**Starting point:** The repository initially had no application files. Phase 1 is now implemented and verified; later phases remain unchecked.

## Progress rules

- Work through mandatory phases in order; the dependencies below identify what each phase needs.
- Change a task from `- [ ]` to `- [x]` only after its implementation and verification are complete.
- Check the phase in the overview only when all its tasks and completion criteria pass.
- Record the date, PR or commit, and verification evidence in the completion log at the bottom.
- If a later change breaks completed work, reopen its checkbox and record the reason.
- Keep unit tests and documentation with each feature. Do not defer all testing to the final audit.
- Use a feature branch and reviewed PR for each phase or smaller feature. Never merge without the required approval.
- Bonus work is optional and does not block mandatory completion.

## Phase overview

- [x] Phase 1 — Architecture, contracts, and runnable project skeleton
- [x] Phase 2 — Containerized databases and local infrastructure
- [ ] Phase 3 — Jenkins, SonarQube, and PR workflow
- [ ] Phase 4 — Authentication, authorization, and secure access
- [ ] Phase 5 — User and traveler management
- [ ] Phase 6 — Travel and itinerary management
- [ ] Phase 7 — Payment methods and gateway integrations
- [ ] Phase 8 — Responsive Admin Dashboard
- [ ] Phase 9 — Request tracing and operational visibility
- [ ] Phase 10 — Automated deployment with Ansible
- [ ] Phase 11 — Replicas, load balancing, and failure recovery
- [ ] Phase 12 — Security review and full audit rehearsal
- [ ] Bonus A — Extended application and database documentation
- [ ] Bonus B — Automated integration and browser E2E suites
- [ ] Bonus C — Kubernetes managed with Ansible

The 2026-10-01 audit follow-up reopens phases whose full completion checks lack implementation or recorded runtime evidence. The checked subitems below still identify work already present; the dated findings and remaining limits are in [the project audit](docs/audit/project-audit-2026-09-29.md) and [runtime follow-up](docs/audit/runtime-verification-2026-10-01.md).

## Scope and design rules

- Build the admin and infrastructure portion of the project. Public booking, ticketing, and a customer-facing checkout are outside the mandatory scope.
- Support administrator CRUD for users, travels, payment methods, and the related records that those operations affect.
- The audit uses “travelers,” whereas the exercise uses “travels.” Cover both with traveler profiles attached to users and a separate travel-management domain.
- Every travel includes one or more destinations, start/end dates, duration, activities, accommodation, and transportation information.
- Support Stripe and PayPal through working sandbox integrations. Provider-name fields alone are not sufficient evidence of integration; real charges are not needed for the audit.
- Require an authenticated `ADMIN` role for business APIs at both gateway and service boundaries. Login and necessary authentication endpoints are exceptions; health/metrics remain private. Any provider callback must use provider-specific verification and cannot expose admin operations.
- Encrypt network traffic, including internal service, database, secrets, and monitoring connections. Expose only the HTTPS entry point publicly; restrict operational interfaces to authenticated administration access.
- Keep secrets in Vault, use separate least-privilege service/database identities, and avoid credentials, tokens, or payment details in logs.
- Each service owns its schema or database. No service reads another service's tables directly, and there are no cross-service database foreign keys.
- Use local transactions for same-service changes. For cross-service changes, use durable operation records, idempotent calls, retries, and visible pending/failed states; do not pretend a distributed change is one atomic database transaction.
- Run multiple instances of every application microservice, including authentication and the gateway, and demonstrate routing around failed instances.
- Containerizing a database does not itself provide database replication. Document database availability limits separately from application replica demonstrations.
- Every feature needs unit tests. Automated integration/E2E suites and Kubernetes are bonuses, while manual cross-service and browser verification remain mandatory.

## Proposed repository layout

This layout includes the implemented Phase 1 foundation and planned infrastructure paths for later phases. Keep feature code grouped by domain inside each service and mirror it under `src/test/java`.

```text
plan.md
README.md
pom.xml                              # Parent build and version management
mvnw, mvnw.cmd, .mvn/                 # Reproducible Maven entry point
auth-service/                        # Credentials, roles, tokens, sessions
user-service/                        # Users, traveler profiles, lifecycle operations
travel-service/                      # Neo4j travels and itinerary components
payment-service/                     # Methods, gateway configuration, provider adapters
api-gateway/                         # Routing and entry-point policy
admin-ui/                            # Browser dashboard
infra/compose/compose.yml             # Local application and database runtime
infra/compose/compose.ci.yml           # Jenkins and SonarQube runtime
infra/compose/compose.observability.yml
infra/proxy/                          # HTTPS entry point and upstream balancing
infra/postgres/                       # Database and role initialization
infra/neo4j/                          # Constraints and initialization
infra/vault/                          # Policies and secret provisioning instructions
infra/ansible/                        # Inventory, playbooks, roles, templates
Jenkinsfile
sonar-project.properties
.github/PULL_REQUEST_TEMPLATE.md
docs/architecture.md
docs/api/                            # OpenAPI contracts per service
docs/data-model.md
docs/security.md
docs/audit/                           # Reproducible scenarios and sanitized evidence
scripts/smoke/                        # API, access-control, and deployment checks
scripts/load/                         # Repeatable load/failure scenarios
```

## Phase 1 — Architecture, contracts, and runnable project skeleton

**Depends on:** Nothing.

**Deliverable:** A buildable workspace with explicit business boundaries, API contracts, and a documented data model.

**Main files:** Root Maven files; each service's `pom.xml`, application entry point, and `src/main/resources/application.yml`; `admin-ui/package.json`; `docs/architecture.md`; `docs/data-model.md`; `docs/api/*.yaml`; `README.md`.

- [x] Record compatible Java, Spring Boot/Cloud, Node, database, and infrastructure versions; pin dependencies and explain the purpose of each major package.
- [x] Define ownership: auth owns credentials/roles/sessions; users owns profiles/travelers; travel owns itineraries; payment owns saved provider references and gateway settings.
- [x] Model stable UUID identifiers and references between domains. Document how a user ID relates to credentials, traveler profiles, travel participation, and payment methods.
- [x] Define lifecycle rules: user deletion revokes access, removes owned traveler/payment data, and removes travel participation without deleting other users' trips; travel deletion removes owned itinerary records while retaining shared destinations.
- [x] Document admin REST contracts for `/api/auth`, `/api/users`, `/api/travelers`, `/api/travels`, `/api/payment-methods`, and `/api/payment-gateways`, including create/read/update/delete routes where applicable.
- [x] Define pagination and a consistent error response containing a stable error code, safe message, field errors, and trace ID. Specify `400`, `401`, `403`, `404`, `409`, and dependency-unavailable responses.
- [x] Scaffold the Maven parent, independent service modules, gateway, and UI. Give each service its own configuration, health endpoint, and container build context.
- [x] Add meaningful starter checks for application startup and gateway route configuration, and document local build commands.
- [x] **Completion check:** `./mvnw verify` and the UI production build succeed; service boundaries and create/update/delete contracts can be explained from the documentation.

## Phase 2 — Containerized databases and local infrastructure

**Depends on:** Phase 1.

**Deliverable:** A reproducible local environment with persistent PostgreSQL and Neo4j data and private service networking.

**Main files:** `infra/compose/compose.yml`; `infra/postgres/`; `infra/neo4j/`; service `Dockerfile` files and migration resources; `.env.example`; `.gitignore`; `README.md`.

- [x] Add Dockerfiles and Compose services for every application service, the gateway, UI, PostgreSQL, and Neo4j; use health checks and named persistent volumes.
- [x] Create separate PostgreSQL databases or schemas and database users for auth, users, and payments. Apply versioned migrations with Flyway or an equivalent justified tool.
- [x] Initialize Neo4j uniqueness constraints and indexes for travel, itinerary component, and destination identifiers; make initialization safe to repeat.
- [x] Define private networks and avoid publishing database/service ports in the deployment profile. Restrict any development-only published ports to loopback.
- [x] Externalize environment configuration and provide examples containing variable names and safe sample values only.
- [x] Implement bounded database connection retries and readiness behavior so services do not appear ready before required dependencies are available.
- [x] Document resource needs, startup, shutdown, backup, and restore commands for both database systems.
- [x] **Completion check:** `docker compose -f infra/compose/compose.yml config` succeeds; startup reaches healthy status, both databases accept authorized connections, and the running stack has been verified with persistent named volumes.

## Phase 3 — Jenkins, SonarQube, and PR workflow

**Depends on:** Phases 1–2.

**Deliverable:** Every PR receives build, unit-test, and code-quality results before review and merge.

**Main files:** `Jenkinsfile`; `sonar-project.properties`; `infra/compose/compose.ci.yml`; `.github/PULL_REQUEST_TEMPLATE.md`; `docs/development.md`; Java/UI test configuration.

- [ ] Provision persistent Jenkins and SonarQube instances and configure authenticated repository integration; inject credentials through protected secret bindings. Compose services exist; Jenkins setup is not demonstrated.
- [ ] Add pipeline stages for checkout, backend build/unit tests, UI lint/unit tests/build, SonarQube analysis, quality-gate enforcement, and artifact archiving. Source stages and focused UI unit tests exist; a live Jenkins run remains open.
- [ ] Check the selected SonarQube edition's PR/branch analysis capabilities. Document and demonstrate a supported per-PR analysis approach if native PR decoration is unavailable.
- [ ] Publish test and analysis results through Jenkins and define PR checks for failed tests and quality gates. A live Jenkins run and Gitea branch protection remain to be recorded.
- [ ] Configure the pipeline to deploy reviewed main-branch artifacts through Ansible once Phase 10 supplies the deployment playbook; PR validation must already work in this phase.
- [x] Define descriptive branch/PR naming, Java/TypeScript naming conventions, reviewer expectations, and approval-before-merge rules in the PR template and development guide.
- [ ] Add a dependency-update and vulnerability-review process; document how upgrades are tested and findings resolved. npm advisory gates and a review process are documented; Java and image vulnerability review still need release evidence.
- [ ] **Completion check:** A deliberately failing test and quality-gate violation each block a sample PR build; a corrected PR publishes passing tests and a clean quality report. Remove the deliberate failures before completing the phase.

## Phase 4 — Authentication, authorization, and secure access

**Depends on:** Phases 1–3.

**Deliverable:** Only authenticated administrators can reach business functionality, with encrypted connections and managed secrets.

**Main files:** `auth-service/src/main/java/`; security configuration in every service; gateway routes/security; `infra/proxy/`; `infra/vault/`; `docs/security.md`; authentication unit tests.

- [x] Implement administrator bootstrap using injected secrets, password hashing, login, short-lived signed access tokens, refresh-token rotation, logout, and session revocation.
- [x] Use `ADMIN` and non-admin roles. Validate signature, expiry, and role in the gateway and every business service.
- [x] Ensure disabling/deleting a user calls the protected auth-service revocation contract before cleanup; existing subject tokens are rejected after revocation.
- [x] Store refresh credentials in secure HTTP-only, Secure, SameSite cookies; document access-token storage and cookie authorization controls.
- [x] Provide a TLS entry proxy configuration and development certificate tooling; internal database/Vault/monitoring certificate deployment remains an environment task.
- [x] Configure a secure Compose overlay for TLS on the browser entry point, PostgreSQL, Neo4j Bolt, Vault, and OTLP; use the trusted local CA tooling for development certificate provisioning.
- [x] Configure Vault policy templates and per-service credential scopes; deployment-time injection, rotation, and unseal procedures are documented in `infra/vault/`.
- [x] Restrict operational endpoints and CI administration to private management/loopback interfaces and document scoped deployment identities.
- [x] Add unit tests for valid credentials, expired/tampered tokens, and revoked sessions; live gateway/service checks cover wrong-role and unauthenticated denial.
- [ ] **Completion check:** Admin requests succeed; unauthenticated requests return `401`; authenticated non-admin requests return `403`, including direct internal API access. Certificate verification succeeds through the secure HTTPS proxy and credentials are absent from repository files and logs. Live TLS/Vault verification remains open.

## Phase 5 — User and traveler management

**Depends on:** Phase 4.

**Deliverable:** Administrators can manage users and traveler profiles with predictable lifecycle behavior.

**Main files:** `user-service/src/main/java/`; `user-service/src/main/resources/db/migration/`; mirrored unit tests; user/traveler OpenAPI contracts; auth internal lifecycle endpoints.

**Current state:** Admin CRUD is validated and persisted to PostgreSQL in the infrastructure profile. Deletion revokes access before disabling the account and removes owned travelers; lifecycle checks and repeat-safe operations are covered by tests and smoke verification.

- [x] Implement paginated list, detail, create, update, and delete endpoints for users and traveler profiles; link each traveler to an existing user.
- [x] Validate required fields and email format/uniqueness; return `409` for duplicate emails and `404` for missing IDs without leaking sensitive fields.
- [ ] Coordinate user creation with auth credential provisioning using an idempotent operation ID; handle provisioning failure without leaving an active account with incomplete identity data. Failed inserts now trigger best-effort identity disablement; durable coordination remains open.
- [x] Implement role/status updates and safe self-management rules, including preventing deletion or demotion of the last active administrator.
- [x] Persist user-deletion progress, revoke access first, and define idempotent cleanup contracts for traveler profiles, payment methods, and travel participation. Wire payment/travel consumers in Phases 6–7.
- [ ] Add unit tests for CRUD success, duplicate emails, invalid traveler ownership, missing users, last-admin protection, provisioning retries, and deletion retries. Coverage exists for selected paths; the complete matrix remains open.
- [ ] **Completion check:** The user/traveler API smoke scenario completes CRUD as an admin and verifies validation failures, access denial, and repeat-safe lifecycle behavior against currently available services. The full failure/retry matrix remains open.

## Phase 6 — Travel and itinerary management

**Depends on:** Phases 4–5.

**Deliverable:** Complete travel CRUD backed by a meaningful Neo4j graph.

**Main files:** `travel-service/src/main/java/`; Neo4j initialization resources; mirrored unit tests; `docs/api/travels.yaml`; `docs/data-model.md`.

**Current state:** Travel and itinerary CRUD validates complete date-bounded itineraries and writes travel and destination nodes through the Neo4j driver in the infrastructure profile; unit and runtime checks cover graph lifecycle behavior.

- [x] Model `Travel`, shared `Destination`, and owned itinerary components for destination stops, activities, accommodation, and transport. Preserve stop ordering and related dates.
- [x] Implement paginated list, detail, create, update, and delete endpoints for travels and the related admin-managed itinerary resources.
- [x] Require at least one destination and valid dates; define duration consistently as calendar days including both start and end dates, and validate component dates against the trip period.
- [x] Store accommodation details and transport mode/departure/arrival information; distinguish an explicitly empty activity list from missing itinerary data.
- [x] Provide destination CRUD and a documented `409` policy for deleting a destination still used by a trip; allow admins to reassign/remove references before deletion.
- [x] Link traveler IDs to travel participation and implement the idempotent user-deletion cleanup contract without removing shared travel records.
- [x] Add unit tests for multi-destination itineraries, duration calculation, reversed dates, missing destinations, missing IDs, shared-node preservation, and retry-safe participant cleanup.
- [ ] **Completion check:** Create and edit a complete multi-destination travel, then delete it and inspect the graph to confirm that no owned orphan nodes remain and shared records survive. Focused graph tests pass; a complete recorded deployed graph inspection remains open.

## Phase 7 — Payment methods and gateway integrations

**Depends on:** Phases 4–6.

**Deliverable:** Admin CRUD for payment methods and gateway settings, with verified Stripe and PayPal sandbox support.

**Main files:** `payment-service/src/main/java/`; payment migration resources; provider adapter unit tests; `docs/api/payments.yaml`; `docs/payments.md`; Vault provider-secret policies.

- [x] Review current official Stripe and PayPal documentation and select supported sandbox flows for creating or connecting reusable payment-method references; document provider account prerequisites and API versions.
- [x] Define a provider adapter interface for connecting, inspecting, updating supported metadata, and detaching/disabling methods; keep provider-specific behavior behind separate Stripe and PayPal adapters.
- [x] Implement admin CRUD for gateway configuration and user-owned payment methods. Store provider IDs, safe display metadata, and Vault secret references; never store raw card numbers or security codes.
- [ ] Distinguish editable local labels/default preferences from provider-controlled fields. Replace a method when its provider cannot edit the underlying funding details.
- [x] Implement both providers' sandbox adapter flows and document the required reference/tokenization handoff.
- [ ] Use timeouts, safe error messages, idempotency keys where supported, and durable pending/retry states when provider and local changes cannot complete together.
- [ ] Reject deleting an in-use gateway with `409` until its methods are detached/reassigned; revoke linked methods through the user-deletion workflow and retain visible failure status if provider cleanup fails.
- [ ] If the selected provider flow requires webhooks, verify signatures, deduplicate event IDs, and reject unverified events.
- [ ] Add adapter/service unit tests for both providers, invalid ownership, duplicate requests, timeouts, provider rejection, missing methods, and cleanup retries. Trusted provider-customer ownership proof remains open.
- [ ] **Completion check:** Demonstrate create/connect, read, supported update, and remove/disable behavior for each provider using sandbox references and controlled provider errors. Sandbox credentials and ownership mapping are unavailable.

## Phase 8 — Responsive Admin Dashboard

**Depends on:** Phases 4–7.

**Deliverable:** A usable dashboard covering every required admin workflow in Chrome and Firefox.

**Main files:** `admin-ui/src/app/`; `admin-ui/src/features/{auth,users,travelers,travels,payments}/`; `admin-ui/src/lib/api.ts`; component/unit tests; `docs/audit/browser-checks.md`.

- [x] Implement login/logout, session expiry handling, protected admin routes, navigation, and a shared API client that handles `401`, `403`, field errors, and service failures consistently.
- [x] Build user and traveler list/detail/create/edit/delete screens with pagination, empty states, field validation, and clear deletion confirmations.
- [x] Build travel screens with multi-destination itinerary editing, dates/duration, activities, accommodation, and transportation controls.
- [x] Build payment-method and gateway-management screens showing provider status, safe metadata, sandbox connection flows, and pending/failed cleanup states.
- [x] Make async actions visibly pending and guard against duplicate submissions; retain form input when recoverable validation or provider errors occur.
- [x] Use responsive navigation, forms, tables/cards, keyboard-accessible controls, visible focus, labels, and accessible error messages.
- [ ] Add unit tests for route protection, form validation, error rendering, and state changes after CRUD operations. Focused login/route and user-create retry tests now exist; the broader form and CRUD matrix remains open.
- [ ] **Completion check:** CRUD controls, responsive layout, and expired-session handling are included in the production build; browser verification steps are documented for Chrome and Firefox. Focused UI tests pass; the full browser matrix remains open.

## Phase 9 — Request tracing and operational visibility

**Depends on:** Phases 4–8.

**Deliverable:** A request can be followed through gateway and downstream services without exposing sensitive data.

**Main files:** `infra/compose/compose.observability.yml`; service logging/telemetry configuration; `docs/operations.md`; `docs/audit/tracing.md`.

- [ ] Emit structured logs containing timestamp, service name, instance ID, severity, trace ID, and safe operation metadata. Request logs now carry these fields; deployed format and redaction verification remains open.
- [x] Propagate request correlation IDs through the gateway and document the OTLP collector integration point.
- [ ] Configure an OpenTelemetry collector and justified trace/log storage and viewing tools with private, authenticated, TLS-protected access. Live export/viewer verification remains open.
- [ ] Record admin mutations with actor ID, operation, target ID, outcome, and time while excluding credentials and sensitive payment data. Structured request logs do not yet establish actor/target mutation auditing.
- [ ] Expose private readiness, liveness, request latency, error count, and instance metrics; define retention and redact sensitive fields. The complete metric and retention proof remains open.
- [x] Add focused tests for context propagation and redaction where application code performs them.
- [x] **Completion check:** Find participating gateway/service logs for successful and failed requests using one propagated request ID; the collector integration point and verification procedure are documented.

## Phase 10 — Automated deployment with Ansible

**Depends on:** Phases 2–9.

**Deliverable:** Ansible provisions and deploys the entire environment repeatably, and Jenkins can deploy reviewed builds.

**Main files:** `infra/ansible/inventories/`; `infra/ansible/site.yml`; `infra/ansible/deploy.yml`; `infra/ansible/roles/`; `Jenkinsfile`; `docs/deployment.md`.

- [ ] Create roles for host prerequisites, Docker, networks, persistent storage, certificates, Vault, PostgreSQL, Neo4j, application services, the proxy, Jenkins, SonarQube, and observability. Debian/Ubuntu Docker preparation is now scripted; other roles and live host setup remain open.
- [ ] Parameterize inventory, resource limits, replica counts, image versions, hostnames, and environment settings; retrieve secrets securely and suppress secret-bearing task output. Release image and secret inputs are parameterized; resource/replica settings and Vault retrieval remain open.
- [ ] Use declarative modules, templates, change-triggered handlers, readiness checks, and repeat-safe migrations; avoid unconditional restarts and destructive volume initialization. Live repeat-run behavior is unverified.
- [ ] Deploy immutable image tags/digests with staged readiness checks and document application rollback plus migration compatibility constraints. SHA tags are wired; registry immutability and live rollback remain unverified.
- [ ] Connect the reviewed main-branch Jenkins pipeline to image publication and the Ansible deployment playbook; retain deployment logs and health-check results. Source wiring exists; no live Jenkins run is recorded.
- [ ] Validate playbook syntax and linting, then run provisioning/deployment on a clean audit environment.
- [ ] Run the same playbooks a second time; inspect recap/change reasons and verify that data remains intact and healthy services are not unnecessarily interrupted.
- [ ] **Completion check:** Clean and repeat deployment procedures, rollback, secret handling, and readiness evidence are documented and wired to the reviewed main-branch pipeline.

## Phase 11 — Replicas, load balancing, and failure recovery

**Depends on:** Phases 9–10.

**Deliverable:** Measured evidence of independent service scaling and continued operation when an instance or service fails.

**Current state:** Compose scaling, Docker-DNS balancing, bounded request handling, repeatable load smoke, and outage/recovery procedures are provided. Database high availability remains explicitly outside this single-host demonstration.

**Main files:** Deployment Compose/proxy configuration; Ansible replica settings; `scripts/load/`; `docs/audit/resilience.md`; service timeout/retry configuration.

- [ ] Run at least two instances of each application microservice and gateway, with balancing at ingress and downstream service routes; avoid fixed container names or host-port collisions that prevent scaling. No two-replica run is recorded.
- [x] Keep authentication and request handling compatible with multiple instances; place revocation/session state in the shared owned datastore rather than process memory.
- [ ] Route only to healthy instances and support graceful shutdown. Add bounded retries for safe operations, idempotency for retried writes, and timeout/circuit-breaker behavior for dependencies. A single-replica backend replacement probe passed; full behavior remains open.
- [ ] Create an authenticated repeatable load scenario with representative user, travel, and payment metadata operations. Set and record an environment-specific latency/error target and concurrency before running it.
- [ ] Verify instance IDs in logs show balanced traffic and that increasing only one service's replica count does not require rebuilding or restarting unrelated services.
- [ ] Stop one instance while load runs; confirm traffic reaches the survivors, data stays consistent, and recovery restores capacity.
- [ ] Stop all instances of one business service; verify unrelated admin areas remain usable and affected operations fail clearly without cascading timeouts. Restore the service and confirm pending work can recover.
- [ ] Restart database containers and demonstrate persistence/reconnection. Document single-host, ingress, and database failure limits rather than claiming full infrastructure high availability.
- [ ] **Completion check:** Replica configuration, load script, instance-loss, outage, and recovery procedures are documented and repeatable.

## Phase 12 — Security review and full audit rehearsal

**Depends on:** All mandatory phases above.

**Deliverable:** A reproducible, passing walkthrough of the exercise and audit, with evidence and no unresolved blocking defects.

**Main files:** `docs/audit/checklist.md`; `docs/audit/evidence.md`; `scripts/smoke/`; `README.md`; any focused fixes found during rehearsal.

- [ ] Convert every question in the linked audit into a local checklist with a demonstration command/action and evidence location; re-read the upstream exercise and audit for changes before final delivery.
- [ ] Demonstrate architecture boundaries, independent deployment/scaling, gateway routing, partial-failure behavior, and cross-service tracing. Gateway trace and backend replacement probes are recorded; independent scale proof remains open.
- [ ] Demonstrate PostgreSQL and Neo4j schemas with real stored examples and explain ownership, constraints, indexes, transactions, and cleanup rules. Local persistence evidence exists; full audit demonstration remains open.
- [ ] Run administrator create/read/update/delete scenarios for users, travelers, travels, payment methods, gateways, and related itinerary records; include duplicate, missing, invalid, and dependency-failure cases.
- [ ] Finish and verify the complete user-deletion workflow across auth, users, travel, and payments; retry interrupted cleanup and confirm no active credentials or orphaned owned references remain.
- [ ] Verify unauthenticated/non-admin denial on every business endpoint, direct-service protection, least-privilege database/Vault policies, verified TLS on every network path, and private operational interfaces.
- [ ] Inspect repository/history and logs for accidental secrets; rotate any exposed credentials and verify documented credential/certificate rotation procedures.
- [ ] Run the full backend unit suite and UI unit/lint/build checks; review the latest PR SonarQube report for blocking errors, dependency warnings, and unresolved vulnerabilities. Backend passed; live PR report remains open.
- [ ] Re-run clean and repeat Ansible deployments, load balancing/failover scenarios, and Chrome/Firefox CRUD walkthroughs on the final build.
- [ ] Prepare reviewed PR examples, naming conventions, Jenkins build/test/deploy evidence, SonarQube reports, and a package-selection explanation.
- [x] Ensure README instructions allow another developer to configure secrets, start the system, obtain the bootstrap admin, run tests, and reproduce the audit without undocumented steps.
- [ ] **Completion check:** Every mandatory audit item has a repeatable local demonstration and recorded evidence on the final build.

## Optional bonuses

### Bonus A — Extended documentation

**Depends on:** Phase 12.

- [ ] Publish complete application/API guidance with examples, diagrams, and a service responsibility map.
- [ ] Document PostgreSQL schemas and Neo4j graph structure, relationship ownership, migration strategy, and backup/restore procedures with worked examples.
- [ ] **Completion check:** A developer unfamiliar with the implementation can follow the documentation to run the project and explain its data relationships.

### Bonus B — Automated integration and E2E tests

**Depends on:** Phase 12.

- [ ] Add real PostgreSQL/Neo4j integration tests using Testcontainers or an equivalent isolated setup, covering migrations, transactions, constraints, and cleanup.
- [ ] Automate multi-service user lifecycle and authorization scenarios, including interrupted deletion and retry recovery.
- [ ] Add Playwright browser tests for login, all admin CRUD flows, responsive behavior, and error paths in Chromium and Firefox.
- [ ] Keep provider mocks for deterministic CI tests and separate credentialed sandbox checks for Stripe and PayPal.
- [ ] **Completion check:** Jenkins runs the suites repeatably with isolated test data and publishes readable failure artifacts.

### Bonus C — Kubernetes with Ansible

**Depends on:** Phase 12.

- [ ] Define deployments, services, ingress, configuration, persistent storage, probes, resource limits, network policies, and secure secret delivery.
- [ ] Use Ansible to install/configure and deploy to the cluster; retain the repeat-run safety checks.
- [ ] Demonstrate replica scheduling, rolling updates, pod replacement, and service load balancing; document database and cluster availability assumptions.
- [ ] **Completion check:** Re-run the mandatory API, security, deployment, and resilience scenarios on Kubernetes successfully.

## Requirement-to-phase map

| Exercise/audit area | Implementation | Final evidence |
| --- | --- | --- |
| Domain boundaries and independent services | 1, 5–7 | Architecture walkthrough and independent rollout in 11–12 |
| API gateway and admin-only APIs | 1, 4 | Endpoint access matrix in 12 |
| PostgreSQL and Neo4j, meaningful schema design | 2, 5–7 | Data model walkthrough and persistence checks in 12 |
| User, traveler, travel, payment and related CRUD | 5–8 | CRUD and deletion scenarios in 12 |
| Destinations, dates, duration, activities, stays, transport | 6, 8 | Multi-destination travel walkthrough in 12 |
| Stripe and PayPal support | 7–8 | Provider sandbox demonstrations in 7, 12 |
| Authentication, roles, TLS, secrets, least privilege | 4 and all later features | Security checks in 12 |
| Chrome/Firefox and responsive UI | 8 | Browser checklist in 8, 12 |
| Unit tests for each feature | 1, 4–9 | Jenkins reports in 3, 12 |
| Jenkins build/test/deploy and SonarQube | 3, 10 | Passing reviewed PR and deployment in 12 |
| PR review, naming, dependency and security hygiene | 3 and each phase | Reviewed PR and quality-report examples in 12 |
| Docker and repeat-safe Ansible deployment | 2, 10 | Clean/repeated deployment evidence in 12 |
| Logs and tracing between services | 9 | Trace walkthrough in 12 |
| Multiple replicas, balancing, load, failover | 11 | Load/failure/recovery evidence in 11–12 |
| Detailed docs, integration/E2E, Kubernetes | Bonuses A–C | Separate bonus demonstrations |

## Completion log

Add a row whenever a phase is completed or reopened. Phase completion tracks implementation and verification; PR approval is a separate requirement before merge.

| Phase | Status | Date | PR / commit | Verification commands or evidence | Remaining issues / reopen reason |
| --- | --- | --- | --- | --- | --- |
| 1 | Complete | 2026-09-11 | Working tree on `feat/phase-1-foundation`; human PR review pending | Maven verify: 23 passing tests; UI production build; four clean OpenAPI contracts; [verification details](docs/phase-1-verification.md) | Docker image execution unverified because daemon is unavailable; upstream test/runtime warnings recorded in verification notes. |
| 3 | Reopened | 2026-10-01 | `build-security-data-fixes` | Dashboard JUnit, npm advisory, and Ansible source gates added; local checks in the [essentials follow-up](docs/audit/essentials-2026-10-01.md) passed. | Configured Jenkins/Sonar/Gitea PR gate and deliberate failure demonstration remain open. |
| 4 | Reopened | 2026-10-01 | `build-security-data-fixes` | Auth/API smoke and token revocation passed in the [local runtime run](docs/audit/runtime-verification-2026-09-30.md). | Live TLS and Vault verification remain open. |
| 5 | Reopened | 2026-10-01 | `build-security-data-fixes` | User lifecycle smoke passed; failed-insert compensation has focused tests in the [follow-up](docs/audit/runtime-verification-2026-10-01.md). | Durable auth/users creation coordination and the full failure/retry matrix remain open. |
| 6 | Reopened | 2026-10-01 | `build-security-data-fixes` | Neo4j aggregate tests and runtime persistence passed. | The complete deployed graph inspection and contract alignment remain open. |
| 7 | Reopened | 2026-10-01 | `build-security-data-fixes` | Persistence and provider adapters have code and unit checks. | Sandbox credentials and customer ownership proof are unavailable; lifecycle/API items remain open. |
| 8 | Reopened | 2026-10-01 | `build-security-data-fixes` | Production build, limited browser smoke, and focused route, user-create, and payment-default UI tests passed. | Broader form/CRUD tests and the Chrome/Firefox responsive matrix remain open. |
| 9 | Reopened | 2026-10-01 | `build-security-data-fixes` | Request IDs reached gateway, auth, and user logs in the [follow-up](docs/audit/runtime-verification-2026-10-01.md); source now emits process instance IDs. | Deployed instance/format check, mutation audit records, collector export/viewing, and full metric/retention proof remain open. |
| 10 | Reopened | 2026-10-01 | `build-security-data-fixes` | Debian/Ubuntu host preparation playbook passes local syntax/lint in the [essentials follow-up](docs/audit/essentials-2026-10-01.md). | Clean/repeat/rollback target runs, other provisioning roles, and Jenkins deployment evidence remain open. |
| 11 | Reopened | 2026-10-01 | `build-security-data-fixes` | Backend replacement with changed IPs passed with the gateway left running. | Two-replica load, balancing, outage, and database recovery demonstrations remain open. |
| 12 | Reopened | 2026-10-01 | `build-security-data-fixes` | [Project audit](docs/audit/project-audit-2026-09-29.md) and dated local runs list completed checks. | Live CI/deployment, providers, TLS/Vault, full browser, and replica evidence remain open. |
