# Let's Travel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan phase by phase. Keep the checkboxes current and do not mark an item complete until its tests and evidence pass.

**Goal:** Extend the existing Travel Plan baseline into the complete role-based Let's Travel platform described by the official subject, and make every mandatory audit question demonstrable.

**Architecture:** Preserve the current auth, user, travel, payment, gateway, React, PostgreSQL, and Neo4j foundations. Add an independently deployable search service backed by Elasticsearch and an engagement service for subscriptions, feedback, reports, and dashboard projections. Use durable outbox/inbox synchronization so PostgreSQL, Neo4j, and Elasticsearch remain recoverable without unsafe distributed transactions.

**Tech Stack:** Java 21, Spring Boot, Spring Security, Spring Cloud Gateway, Maven, PostgreSQL, Neo4j, Elasticsearch, React, TypeScript, Docker Compose, Jenkins, SonarQube, Ansible, Vault, and OpenTelemetry.

**Spec:** [Official Let's Travel subject](https://github.com/01-edu/public/tree/master/subjects/java/projects/lets-travel) and [official audit](https://github.com/01-edu/public/blob/master/subjects/java/projects/lets-travel/audit/README.md), reviewed on 2026-10-02.

## How to use this plan

- Work through the mandatory phases in order unless a phase explicitly says it can run in parallel.
- Change `- [ ]` to `- [x]` only after the implementation, automated tests, and phase completion gate all pass.
- Do not mark an item complete because a stub, UI-only mock, or documentation-only version exists.
- Reopen an item if a later change breaks its behavior or invalidates its evidence.
- Record the completion date, commit or PR, commands run, and evidence link in the completion log.
- Add focused tests in the same change as every business rule. Do not postpone testing until the final phase.
- Bonus phases never block completion of the mandatory project.

## Starting point

The repository is a copy of the earlier Travel Plan implementation, not an empty project. It already contains:

- Auth, user, travel, payment, gateway, and React application modules.
- PostgreSQL persistence for auth, users, and payments.
- Neo4j-backed travel and itinerary storage.
- Admin-oriented CRUD screens and APIs.
- Docker Compose, Jenkins, SonarQube, Ansible, Vault, TLS, and observability foundations.

These foundations must be verified as part of Phase 0. Their presence does not automatically satisfy the new Let's Travel requirements. In particular, the current system is admin-focused and does not yet provide Elasticsearch search, autocomplete, personalized recommendations, traveler subscriptions, feedback, reports, role dashboards, or the `TRAVEL_MANAGER` and `TRAVELER` experiences.

## Target service ownership

| Component | Owns | Primary store |
| --- | --- | --- |
| `auth-service` | Credentials, password hashes, roles, sessions, token revocation | PostgreSQL |
| `user-service` | User profiles, privacy preferences | PostgreSQL |
| `travel-service` | Travel listings, manager ownership, itineraries, recommendation graph | Neo4j |
| `engagement-service` (new) | Subscriptions, cancellation history, feedback, reports, dashboard projections, public manager pages | PostgreSQL |
| `payment-service` | Saved payment methods and subscription-payment lifecycle | PostgreSQL + provider APIs |
| `search-service` (new) | Search/autocomplete API and Elasticsearch index projection | Elasticsearch |
| `api-gateway` | Public routing, authentication, coarse authorization, request IDs | None |
| `admin-ui` | One responsive, role-aware web application despite its legacy directory name | Browser |

Elasticsearch is a derived read model, never the source of truth. Cross-service changes use local transactions, durable outbox records, idempotent consumers, retries, and visible reconciliation state.

## Global constraints

- Roles are `ADMIN`, `TRAVEL_MANAGER`, and `TRAVELER`.
- An Admin can perform every Travel Manager and Traveler action, plus admin-only oversight actions.
- A Travel Manager can perform every Traveler action and can manage only their own travel offerings, subscribers, feedback, and analytics.
- A Traveler can browse, search, receive recommendations, subscribe, pay, cancel within policy, leave eligible feedback, report users/managers/travels, and view personal statistics.
- Cancellation is allowed when the travel-local calendar date is on or before `startDate - 3 days`; it is rejected after that cutoff. Boundary tests are required.
- Recommendations must use previous participation and feedback plus at least three travel fields. The initial fields are destination, activity category, transport mode, and accommodation category.
- Passwords must be one-way hashed. Raw passwords, access/refresh tokens, provider secrets, full payment details, and sensitive personal data must never be logged.
- All public traffic uses verified TLS. Production service, database, secrets, and telemetry traffic must also use authenticated encrypted connections.
- SQL and Cypher access must be parameterized. User-generated content must be safely rendered and protected by a restrictive Content Security Policy.
- Core application actions must complete in under five seconds at the documented audit load. Search/autocomplete should target a substantially lower latency and record its measured percentile.
- Each phase must leave the workspace buildable and independently reviewable.

## Review focus

- Role inheritance must grant permitted lower-role actions without allowing managers to modify another manager's resources.
- Exactly-three-days cancellation behavior must be deterministic across time zones and daylight-saving changes.
- Retries, duplicate events, or temporary Elasticsearch/Neo4j/payment outages must not create duplicate subscriptions or charges.
- Feedback and recommendations must exclude ineligible, private, deleted, or future participation data.
- Dashboard totals must reconcile with source subscriptions and payments instead of silently drifting.

## Phase overview

- [x] Phase 0 — Baseline verification and architecture contracts
- [x] Phase 1 — Roles, identities, and authorization
- [x] Phase 2 — Manager-owned travel offerings and public profiles (one item deferred to Phase 3: protecting confirmed subscriptions)
- [x] Phase 3 — Subscriptions, subscriber management, and cancellation policy
- [ ] Phase 4 — Secure subscription payments
- [ ] Phase 5 — Elasticsearch search and autocomplete
- [x] Phase 6 — Feedback, reports, and moderation
- [ ] Phase 7 — Neo4j personalized recommendations
- [ ] Phase 8 — Analytics and role-specific dashboard APIs
- [ ] Phase 9 — Responsive role-specific web experience
- [ ] Phase 10 — Consistency, resilience, scalability, and performance
- [ ] Phase 11 — Security, privacy, and compliance hardening
- [ ] Phase 12 — Test automation, CI quality gates, and deployment
- [ ] Phase 13 — Documentation and final audit rehearsal
- [ ] Bonus A — Progressive Web App
- [ ] Bonus B — Multilingual support
- [ ] Bonus C — Innovative engagement feature

---

## Phase 0 — Baseline verification and architecture contracts

**Depends on:** Nothing.

**Deliverable:** A verified baseline, agreed service boundaries, versioned APIs, and data-flow contracts for all Let's Travel additions.

**Main files:** `pom.xml`, service `pom.xml` files, `docs/architecture.md`, `docs/data-model.md`, `docs/api/`, `docs/versions.md`, `infra/compose/`, `README.md`.

- [x] Run the existing Maven, UI, API-lint, and Compose configuration checks and record the real baseline result.
- [x] Inventory existing implemented behavior separately from stubs, documentation, and unverified infrastructure claims.
- [x] Add `engagement-service` and `search-service` to the Maven reactor with startup tests, health probes, Dockerfiles, and private management ports.
- [x] Add PostgreSQL ownership/migrations for engagement data and Elasticsearch configuration with a pinned compatible version.
- [x] Update architecture and data-model documentation with service ownership, allowed dependencies, and prohibited direct database access.
- [x] Define versioned OpenAPI contracts for subscriptions, feedback, reports, search, autocomplete, recommendations, dashboards, and the public manager page.
- [x] Define standard pagination, validation errors, trace IDs, idempotency keys, and dependency-unavailable responses across the new APIs.
- [x] Define a durable event envelope containing `eventId`, `eventType`, `aggregateId`, `aggregateVersion`, `occurredAt`, and trace context.
- [x] Define per-service outbox/inbox, retry, dead-letter/reconciliation, and full read-model rebuild procedures.
- [x] Document the role-capability matrix and data flow from a travel write to Neo4j, Elasticsearch, engagement projections, and dashboards.
- [x] Add architecture tests or dependency rules that prevent one service from importing another service's domain classes.
- [x] **Completion gate:** A clean checkout builds, all modules start, OpenAPI lint passes, Compose resolves, and the team can explain service ownership and cross-store consistency from the documentation. Evidence: `docs/audit/phase-2-verification-2026-10-07.md` (runtime startup).

## Phase 1 — Roles, identities, and authorization

**Depends on:** Phase 0.

**Deliverable:** Secure login and authorization for Admins, Travel Managers, and Travelers at both gateway and service boundaries.

**Main files:** `auth-service/src/main/`, `user-service/src/main/`, service security packages, `api-gateway/src/main/`, `admin-ui/src/lib/`, auth/user migrations and tests.

- [x] Replace the legacy `OPERATOR` role with explicit `TRAVEL_MANAGER` and `TRAVELER` roles, including a safe migration for existing records.
- [x] Include immutable user ID, role, issuer, audience, issued-at, expiry, and session/revocation identity in signed tokens.
- [x] Enforce the role hierarchy: Admin includes manager/traveler capabilities; manager includes traveler capabilities; traveler has no management capability.
- [x] Enforce resource ownership in receiving services, not only by hiding UI controls or filtering at the gateway.
- [x] Add a safe `/api/me` endpoint so the UI can load the authenticated user's profile and role without trusting browser state.
- [x] Support creation, activation, disabling, role changes, and deletion for manager/traveler identities while preserving last-admin protection.
- [x] Revoke active sessions immediately when an identity is disabled, deleted, or moved to a less privileged role.
- [x] Reject stale, tampered, expired, revoked, wrong-issuer, wrong-audience, and unsupported-role tokens.
- [x] Add an authorization matrix test covering every new endpoint for unauthenticated, wrong-role, right-role, owner, and non-owner access.
- [x] Verify the same denials when a service is called directly and the gateway is bypassed.
- [x] **Completion gate:** All three roles can sign in and access exactly their permitted routes; forbidden cross-user and direct-service operations are covered by automated tests and return `403` (role too low) or `404` (another user's resource) without leaking data. Cross-manager travel cases are added with manager-owned travels in Phase 2.

## Phase 2 — Manager-owned travel offerings and public profiles

**Depends on:** Phase 1.

**Deliverable:** Travel Managers can create and manage only their own complete travel offerings, while travelers can browse offerings and inspect manager transparency information.

**Main files:** `travel-service/src/main/`, `user-service/src/main/`, Neo4j constraints, `docs/api/travels.yaml`, new manager-profile API contract, matching tests.

- [x] Add immutable `managerId` ownership to every travel and backfill or explicitly retire legacy ownerless records.
- [x] Let managers create, view, update, publish/unpublish, and delete their own travel offerings.
- [x] Let Admins perform the same operations for any manager and let Travelers read only published, bookable offerings.
- [x] Prevent manager ownership reassignment through normal update APIs; provide a separate audited Admin operation if reassignment is required.
- [x] Preserve complete travel details used by search and recommendations: destinations, dates, activities, accommodation, transport, capacity, price/currency, and lifecycle status.
- [x] Validate date ranges, capacity, nonnegative prices, currency, itinerary bounds, and publication readiness.
- [ ] Prevent edits that would invalidate confirmed subscriptions; define allowed post-subscription changes and notify affected subscribers. Phase 3: once places are taken, dates, time zone, price and currency are fixed (`TRAVEL_HAS_SUBSCRIBERS`) and capacity cannot drop below occupancy; other changes emit `TRAVEL_UPDATED`. Delivering notifications to subscribers is still open.
- [x] Add a public manager page API containing manager summary, completed-travel statistics, past ratings, and report count without exposing private report content. Ratings and reports are empty/zero until Phase 6.
- [x] Emit versioned travel-created/updated/published/unpublished/deleted events for downstream search and analytics projections.
- [x] Add tests for Admin override, manager ownership, published visibility, invalid changes, and concurrent updates.
- [x] **Completion gate:** Two manager accounts can independently manage their own listings, cannot mutate each other's listings, and travelers can browse only eligible published offerings and view safe manager profiles.

Evidence: `docs/audit/phase-2-verification-2026-10-07.md`.

## Phase 3 — Subscriptions, subscriber management, and cancellation policy

**Depends on:** Phases 1–2.

**Deliverable:** Travelers can subscribe and unsubscribe safely, and managers can manage subscriber lists for their own travels.

**Main files:** New `engagement-service/src/main/`, engagement migrations, travel/user internal clients, subscription OpenAPI contract, service tests.

- [x] Model subscription states such as `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED_BY_TRAVELER`, `CANCELLED_BY_MANAGER`, `PAYMENT_FAILED`, and `COMPLETED`.
- [x] Store travel, manager, traveler/user, price/currency snapshot, cutoff date, payment reference, timestamps, and cancellation reason.
- [x] Add an idempotent subscribe operation that validates an active traveler, published future travel, remaining capacity, and no active duplicate subscription.
- [x] Protect the final available place against concurrent oversubscription with a documented atomic reservation strategy.
- [x] Calculate the cancellation cutoff from the travel-local calendar and allow cancellation exactly three days before start, but reject later traveler cancellations.
- [x] Add explicit boundary tests for four days before, exactly three days before, less than three days before, the start date, time-zone changes, and repeated requests.
- [x] Let managers and Admins list subscribers for authorized travels, view safe profile summaries, and unsubscribe a traveler with an audit reason.
- [x] Prevent managers from viewing or changing subscriber lists belonging to another manager.
- [x] Release reserved capacity after failed payment or valid cancellation and make every retry idempotent.
- [x] Maintain traveler cancellation history for personal statistics without exposing it to unrelated users.
- [x] Emit subscription lifecycle events for payment, Neo4j participation/recommendation data, notifications, and dashboards. Written to the engagement `outbox_event` table in the same transaction; the feed publisher arrives with the event pipeline.
- [x] **Completion gate:** Subscription, capacity, manager removal, and all cutoff boundary scenarios pass through the API with concurrency and retry tests.

Evidence: `docs/audit/phase-3-verification.md`. Design: `docs/subscriptions.md`.

## Phase 4 — Secure subscription payments

**Depends on:** Phase 3 and the existing payment foundation.

**Deliverable:** A traveler can pay for a reserved subscription using supported payment methods without duplicate charges or exposed payment data.

**Main files:** `payment-service/src/main/`, payment migrations, provider adapters, `engagement-service` payment client/consumer, `docs/payments.md`, API contracts and tests.

- [x] Add a subscription-payment aggregate linked to subscription ID, user ID, amount, currency, provider, provider reference, and lifecycle state.
- [x] Reuse validated saved payment methods while proving that the authenticated user owns the selected method.
- [x] Support the configured Stripe and PayPal sandbox flows and at least one clear test/simulation path when external credentials are unavailable. (Stripe/PayPal adapters are verified against local stand-ins and the payment simulator; no credentialed sandbox charge has been run.)
- [x] Keep payment provider secrets in Vault/deployment secrets and prevent storage or logging of raw card or account credentials.
- [x] Make payment creation idempotent across browser retries, gateway retries, provider timeouts, and event redelivery.
- [x] Persist intent before provider calls and reconcile ambiguous provider responses instead of issuing an immediate second charge.
- [x] Confirm the subscription only after a verified successful payment; release capacity after a terminal payment failure.
- [x] Define cancellation/refund behavior explicitly and show the resulting payment and subscription state to the traveler.
- [x] Return safe, actionable failure messages without leaking provider internals or sensitive data.
- [x] Add tests for wrong-owner method use, declined payment, provider timeout, duplicate request, callback verification, reconciliation, and successful confirmation.
- [ ] **Completion gate:** Stripe and PayPal sandbox/simulation journeys produce exactly one payment, correct subscription state, redacted logs, and recover correctly from a forced timeout. (Proven by automated simulation tests, see `docs/audit/phase-4-verification.md`; still open: the manual Compose demo run and a credentialed Stripe/PayPal sandbox run.)

## Phase 5 — Elasticsearch search and autocomplete

**Depends on:** Phases 0 and 2. It may be implemented in parallel with Phases 3–4 after travel events are stable.

**Deliverable:** Fast, relevant search and autocomplete across complete published travel details.

**Main files:** New `search-service/src/main/`, index mappings/templates, `infra/compose/`, gateway routes, search OpenAPI contract, load/integration tests.

- [x] Define a versioned travel index mapping covering name, destinations, country, description, dates, activities, accommodation, transport, manager, price, and availability.
- [x] Normalize keyword/text fields, analyzers, case/diacritic behavior, and autocomplete prefix fields.
- [x] Consume travel events idempotently and delete or hide documents immediately after unpublish/delete events. (Phase 5 uses after-commit change notifications plus fetch-current-state, not the outbox feed; see docs/search.md §5.)
- [x] Add a full reindex command that rebuilds a fresh versioned index and switches an alias without search downtime.
- [x] Expose search with pagination, filters, sorting, safe query limits, and deterministic empty-query behavior.
- [x] Expose debounced autocomplete with a small result limit and enough context to distinguish similarly named travels.
- [x] Rank exact/prefix matches above fuzzy matches and verify relevance with a documented representative data set.
- [x] Reject malformed or abusive queries and avoid exposing Elasticsearch query syntax directly to clients.
- [x] Provide lag/readiness metrics and an operational view of failed indexing events.
- [x] Implement a documented degraded browse fallback when Elasticsearch is unavailable; clearly indicate reduced search capability.
- [x] Add integration tests using a real Elasticsearch test container for mapping, search, autocomplete, updates, deletes, replay, and outage recovery.
- [x] Measure autocomplete/search latency under the documented load and keep every audited action below five seconds.
- [x] **Completion gate:** Search returns accurate results across all travel details, autocomplete is relevant and prompt, reindex/replay repairs drift, and a forced Elasticsearch outage does not break core browsing.

## Phase 6 — Feedback, reports, and moderation

**Depends on:** Phase 3.

**Deliverable:** Eligible travelers can leave feedback and file reports, while managers and Admins receive the appropriate quality-control views.

**Main files:** `engagement-service/src/main/`, migrations, feedback/report contracts, user/travel internal clients, tests.

- [x] Allow one feedback record per completed participation with rating, bounded comment, author, travel, manager, and timestamps.
- [x] Reject feedback from nonparticipants, cancelled subscriptions, future/incomplete travels, and duplicate submissions.
- [x] Define whether feedback can be edited and for how long; keep an audit trail for moderation-sensitive changes. (Feedback is immutable once submitted; report moderation keeps an append-only history.)
- [x] Let the owning manager read feedback for their travels and let Admins read feedback across all travels.
- [x] Expose only the intended public rating summary; protect feedback author privacy where required.
- [x] Allow Travelers to report a Travel Manager, another Traveler, or a Travel with a reason category and bounded description.
- [x] Prevent self-reporting, invalid targets, duplicate report spam, and unrestricted access to report details.
- [x] Give Admins a moderation queue with status, assignee, resolution, notes, and an immutable action history.
- [x] Show safe report counts on public manager/traveler views without exposing reporters or private allegations. (Upheld count on the manager page and in the subscriber summary; there is no public traveler page yet.)
- [x] Emit feedback/report events for recommendations, manager performance, dashboards, and notifications. Written to the engagement `outbox_event` table in the same transaction (`FeedbackSubmitted`, `ReportFiled`, `ReportModerated`); the feed publisher arrives with the event pipeline.
- [x] Sanitize and safely render every user-supplied text field; add stored/reflected XSS regression tests. (Plain text with length limits, parameterised SQL, JSON + `nosniff`, React text rendering; the restrictive CSP is Phase 11.)
- [x] **Completion gate:** Eligibility, visibility, moderation, report-target, privacy, and XSS tests pass for every role.

Evidence: `docs/audit/phase-6-verification.md`. Design: `docs/feedback-and-reports.md`.

## Phase 7 — Neo4j personalized recommendations

**Depends on:** Phases 2, 3, and 6.

**Deliverable:** Each traveler receives explainable travel suggestions based on their own participation and feedback history.

**Main files:** `travel-service/src/main/`, Neo4j migrations/constraints, engagement event consumers, recommendation API contract and tests.

- [x] Extend the graph with traveler preference/participation relationships and the selected travel fields: destination, activity category, transport mode, and accommodation category.
- [x] Project confirmed/completed participation and eligible feedback into Neo4j idempotently without storing unnecessary personal data.
- [x] Document a deterministic scoring formula using at least three travel fields plus feedback weight and prior participation.
- [x] Exclude unpublished, cancelled, full, already completed, and otherwise ineligible travels from recommendations.
- [x] Avoid recommending the same result repeatedly when suitable alternatives exist and define stable tie-breaking.
- [x] Return concise recommendation reasons such as matched destination and activity preferences without exposing other users' data.
- [x] Provide a cold-start fallback using popular, well-rated upcoming travels when the traveler lacks sufficient history.
- [x] Add replay/rebuild and drift-detection procedures for recommendation relationships.
- [x] Test two users with deliberately different participation and feedback histories and prove their ranked results differ appropriately.
- [x] Test cold start, negative feedback, deleted feedback, deleted user, full travel, and Neo4j outage behavior. (Feedback cannot be deleted through the API; a rating that disappears from engagement-service is removed by the reconciliation. The outage is tested at service level; see [recommendations](docs/recommendations.md#7-evidence).)
- [ ] **Completion gate:** (Automated two-account evidence in `RecommendationServiceTest`; still open: a live run on the full stack.) The two-account audit scenario yields explainable, distinct, reproducible recommendations and the fallback remains useful when history or Neo4j is unavailable.

## Phase 8 — Analytics and role-specific dashboard APIs

**Depends on:** Phases 2–7.

**Deliverable:** Complete, reconcilable Admin, Travel Manager, Traveler, and public-manager statistics.

**Main files:** `engagement-service/src/main/`, projection migrations/consumers, dashboard API contracts, reconciliation jobs and tests.

- [x] Define time zone, currency-conversion, date-range, inclusion, cancellation, refund, and report-count rules for every metric.
- [x] Build Admin overview metrics for top-ranking managers and travels, income by recent month, organized-travel counts, detailed travel history, feedback, and open/resolved reports.
- [x] Define and document a deterministic manager performance score using feedback, income, participation/traveler volume, completed travel count, and report outcomes.
- [x] Build an ordered manager leaderboard with transparent component values so the score can be explained during the audit.
- [x] Build Travel Manager metrics for own income, number of trips, number of travelers, subscriber status, ratings, feedback, and useful trends.
- [x] Build Traveler metrics for past participation, reports made/received as permitted, subscription cancellations, feedback history, and preferred payment method.
- [x] Complete the public manager statistics page with completed travels, past ratings, and report count.
- [x] Enforce Admin global scope, manager owner-only scope, traveler self-only scope, and privacy-safe public scope.
- [x] Make projection consumers idempotent and provide a reconciliation command that compares dashboards to source records and repairs drift. (Dashboards are computed on request from the source tables, so they cannot drift; `GET /api/dashboards/admin/reconciliation` compares their income with payment-service. See [dashboards](docs/dashboards.md#5-checking-the-totals-against-payments).)
- [x] Add fixture-based tests whose expected totals, rankings, scores, and monthly income can be calculated by hand.
- [ ] **Completion gate:** All dashboard values reconcile with source subscriptions/payments/feedback/reports, role scoping tests pass, and each score/total can be explained from documented rules.

## Phase 9 — Responsive role-specific web experience

**Depends on:** Phases 1–8.

**Deliverable:** One accessible, responsive application that exposes the correct end-to-end journey for each role.

**Main files:** `admin-ui/src/`, UI tests, `docs/audit/browser-checks.md`. The legacy directory may be renamed in a dedicated change if the migration cost is justified.

- [x] Add role-aware navigation and route guards based on the server-validated `/api/me` response.
- [x] Keep the complete Admin workspace and add overview, rankings, travel history, feedback, and moderation views.
- [x] Add Travel Manager listing editor, subscriber management, own feedback, analytics, and traveler capabilities.
- [x] Add Traveler search/autocomplete, browse/detail, recommendations, subscribe/pay/cancel, feedback, reports, manager profile, and personal statistics views.
- [x] Show loading, empty, success, validation, authorization, conflict, payment, degraded-service, and retry states clearly.
- [x] Debounce and cancel stale autocomplete requests; support keyboard navigation and screen-reader announcements.
- [x] Prevent double submission for subscription, cancellation, payment, feedback, and report actions.
- [ ] Use semantic landmarks, associated labels, visible focus, sufficient contrast, and keyboard-operable dialogs/menus.
- [ ] Support at least phone, tablet, and desktop layouts without horizontal overflow or hidden critical actions.
- [ ] Verify current Chrome and Firefox behavior for all three roles and record the browser/version/device matrix.
- [ ] Add component tests for role guards and critical forms plus browser E2E tests for the principal role journeys.
- [ ] **Completion gate:** The full search-to-booking/payment and post-travel feedback journeys work on the responsive browser matrix, and each role can easily reach every permitted feature.

## Phase 10 — Consistency, resilience, scalability, and performance

**Depends on:** Phases 2–9.

**Deliverable:** The system remains correct under retries, load, replica changes, and dependency failures.

**Main files:** Outbox/inbox implementations, reconciliation jobs, `infra/compose/`, `scripts/load/`, `docs/operations.md`, resilience tests and runbooks.

- [ ] Implement transactional outbox/inbox behavior for PostgreSQL services and same-transaction change markers for Neo4j writes.
- [x] Make all consumers idempotent and order events by aggregate version; quarantine unprocessable events with a visible recovery workflow. (Inbox and version checks in travel-service; rejected events are parked and resent through `/api/admin/events`.)
- [ ] Add reconciliation for PostgreSQL/Neo4j/Elasticsearch mismatches and prove it repairs deleted, stale, and duplicate projections.
- [ ] Run at least two instances of each stateless application service and demonstrate independent scaling of search and travel workloads.
- [x] Document honest Elasticsearch and Neo4j database scaling/availability limits; do not confuse application replicas with database clustering.
- [x] Ensure readiness removes unhealthy instances from routing and bounded timeouts/circuit breakers prevent cascading waits. (Bounded timeouts everywhere, circuit breakers, gateway read retries; Docker DNS limitation documented in [operations](docs/operations.md#scaling-and-its-limits).)
- [x] Define fallbacks: browse when search fails, popular travels when recommendations fail, queued indexing when Elasticsearch fails, and visible pending state when payments fail.
- [x] Propagate one trace/request ID from browser through gateway, services, event relays, and provider calls; redact structured logs.
- [x] Create repeatable load scenarios for search/autocomplete, subscriptions, dashboards, and mixed user journeys. (`scripts/load/load.mjs`; drills in `scripts/load/drill.mjs`.)
- [ ] Demonstrate audited actions complete under five seconds at the documented concurrency; capture p50/p95/p99 latency, throughput, errors, and environment details.
- [ ] Kill one service replica and separately stop Elasticsearch, Neo4j, and a payment simulator; verify expected degraded behavior and recovery.
- [ ] Restart stateful services and verify persistence, replay, and projection catch-up without duplicates or data loss.
- [ ] **Completion gate:** Load, failure, recovery, reconciliation, and independent-scale rehearsals are repeatable and their captured evidence matches the documented guarantees.

## Phase 11 — Security, privacy, and compliance hardening

**Depends on:** All feature phases; security tests should start earlier and run continuously.

**Deliverable:** Defense-in-depth for identity, personal data, payments, transport, inputs, secrets, and operational access.

**Main files:** Security configuration in every service, `infra/proxy/`, `infra/vault/`, secure Compose overlays, `docs/security.md`, security tests and scan configuration.

- [x] Verify passwords use an approved adaptive one-way hash with per-password salts and an upgrade path; prove plaintext is never stored.
- [x] Verify secure refresh cookies, CSRF/origin defenses where applicable, short-lived access tokens, rotation, logout, revocation, and safe browser storage.
- [x] Enforce parameterized SQL/Cypher/Elasticsearch access and add SQL-injection and query-injection regression tests.
- [x] Enforce output encoding/sanitization, Content Security Policy, secure headers, dependency integrity, and stored/reflected/DOM XSS tests.
- [x] Validate request sizes, field lengths, enum values, file/content types if introduced, pagination limits, and rate limits for login/search/autocomplete/reports.
- [ ] Enable verified TLS for public, internal service, PostgreSQL, Neo4j, Elasticsearch, Vault, and telemetry connections in the deployment profile.
- [ ] Store and rotate service credentials, signing keys, database credentials, TLS keys, and provider secrets through Vault or approved protected bindings.
- [ ] Apply least-privilege database users, Vault policies, network exposure, machine identities, and private management endpoints.
- [x] Define data minimization, purpose, retention, deletion/anonymization, export/access, consent/notice, and incident-handling procedures for personal and payment-related data.
- [x] Ensure account deletion removes or anonymizes subscriptions, recommendation relationships, payment references, and profile data while retaining only legally justified records.
- [x] Add immutable audit records for privileged identity, moderation, travel ownership, refund, and destructive actions.
- [ ] Run secret, Java/npm dependency, container image, and static analysis scans; triage every high/critical finding with a recorded decision.
- [ ] **Completion gate:** Threat-model scenarios and automated security tests pass, TLS verification covers every network path, credentials are absent from code/logs, and privacy lifecycle evidence is reproducible.

## Phase 12 — Test automation, CI quality gates, and deployment

**Depends on:** Feature work can extend this continuously; final completion depends on Phases 1–11.

**Deliverable:** Reproducible automated verification and deployment with blocking quality/security gates.

**Main files:** `Jenkinsfile`, `sonar-project.properties`, test sources, E2E configuration, `infra/ansible/`, deployment Compose files, CI documentation.

- [ ] Require unit tests for every new rule, including role inheritance, ownership, cutoff boundaries, scoring, eligibility, idempotency, and fallbacks.
- [ ] Add integration tests with real PostgreSQL, Neo4j, and Elasticsearch containers plus payment-provider simulators.
- [ ] Add contract tests for all OpenAPI endpoints and internal event/API consumers.
- [ ] Add browser E2E suites for Admin, Travel Manager, and Traveler happy paths plus critical denials/failures.
- [ ] Publish backend, frontend, integration, E2E, coverage, performance-smoke, and security scan results in Jenkins.
- [ ] Enforce SonarQube quality gates, test failures, API lint, dependency policy, and image scan policy before merge.
- [ ] Prove a deliberately failing test and a deliberate quality-gate violation each block a sample PR; remove both after capturing evidence.
- [ ] Build immutable version/SHA-tagged images for every service and retain an artifact-to-commit manifest.
- [ ] Update Ansible for the two new services, Elasticsearch, migrations, secrets, health checks, rolling deployment, and rollback.
- [ ] Demonstrate clean deployment, repeat deployment, safe migration, rolling update, failed-health rollback, and retained persistent data.
- [ ] Require reviewed PRs, descriptive names, and protected-branch checks; retain representative review evidence.
- [ ] **Completion gate:** A clean PR runs every required gate, an approved main build deploys via Ansible, and the same release can be rolled back without losing committed data.

## Phase 13 — Documentation and final audit rehearsal

**Depends on:** Phases 0–12.

**Deliverable:** Another developer or auditor can reproduce, understand, and evaluate the complete system without undocumented knowledge.

**Main files:** `README.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/security.md`, `docs/operations.md`, `docs/audit/`, `scripts/audit/`.

- [ ] Update setup instructions, prerequisites, safe example configuration, local start/stop, seed data, and troubleshooting for all services.
- [ ] Document how Elasticsearch implements search/autocomplete and how Neo4j produces recommendations from at least three travel fields.
- [ ] Document independent service scaling and operation, plus PostgreSQL/Neo4j/Elasticsearch consistency and recovery techniques.
- [ ] Document the exact capabilities and permissions of Admins, Travel Managers, and Travelers.
- [ ] Document package/library choices, boundaries, public/internal APIs, event schemas, data ownership, indexes, and migrations.
- [ ] Prepare deterministic demo users and data for all roles without committing real credentials or personal information.
- [ ] Run the two-account personalized recommendation comparison required by the audit and retain sanitized results.
- [ ] Run every functional audit journey: search, autocomplete, recommendations, dashboards, subscriptions, cutoff, payments, feedback, listings, subscribers, analytics, profiles, login, and RBAC.
- [ ] Run every nonfunctional audit journey: TLS, secrets, under-five-second load, fallbacks, responsive browsers, privacy, code readability/separation, SQL injection, XSS, and password hashing.
- [ ] Review random code paths as an auditor would and refactor unclear or improperly coupled areas before the final rehearsal.
- [ ] Record commands, expected result, actual result, date, commit SHA, environment, and evidence artifact for every mandatory audit item.
- [ ] Re-read the upstream subject and audit immediately before submission and add any newly introduced requirement.
- [ ] **Completion gate:** Every mandatory audit question maps to passing, reproducible evidence on the final commit, with no unresolved critical defect or undocumented setup step.

---

## Optional bonus phases

### Bonus A — Progressive Web App

- [ ] Add a valid manifest, installable icons, service worker, and secure update strategy.
- [ ] Define useful offline behavior without caching private or payment data unsafely.
- [ ] Add safe background sync for eligible queued actions and push notifications with explicit consent.
- [ ] Measure and optimize mobile load performance using documented PWA checks.
- [ ] Verify install, offline behavior, background sync, notifications, and upgrades on representative mobile devices.
- [ ] **Completion gate:** The PWA audit items work reliably without weakening authentication, privacy, or data freshness.

### Bonus B — Multilingual support

- [ ] Select initial locales and extract every UI message, validation message, email/notification, and formatted date/number/currency.
- [ ] Add an accessible language switcher and persist the preference to the profile with a browser fallback.
- [ ] Define how user-generated content is labeled or translated without misrepresenting the original text.
- [ ] Test route changes, errors, notifications, long translations, missing keys, and locale-specific formatting.
- [ ] **Completion gate:** Users can switch languages seamlessly and the preference persists across sessions throughout the platform.

### Bonus C — Innovative engagement feature

- [ ] Write a short problem statement and measurable success criterion before implementation.
- [ ] Verify the feature adds clear traveler/manager value and does not duplicate a mandatory requirement.
- [ ] Add security, privacy, accessibility, failure, and regression tests equal to the mandatory features.
- [ ] Document the technology/methodology used and demonstrate error-free behavior during the audit.
- [ ] **Completion gate:** The feature demonstrably improves engagement or platform value without destabilizing mandatory functionality.

## Audit traceability matrix

| Official audit area | Primary phase(s) |
| --- | --- |
| Explain Elasticsearch search/autocomplete | 5, 13 |
| Explain Neo4j personalization | 7, 13 |
| Explain independent scaling/operation | 0, 10, 13 |
| Explain PostgreSQL/Neo4j/Elasticsearch consistency | 0, 10, 13 |
| Explain role functionality and permissions | 1, 13 |
| Accurate search results and prompt autocomplete | 5 |
| Two-account personalized recommendation comparison | 7, 13 |
| Complete Admin overview | 8, 9 |
| Helpful Travel Manager statistics | 8, 9 |
| Traveler recommendations and history | 7, 8, 9 |
| Easy travel navigation and subscription | 3, 9 |
| Three-day cancellation cutoff | 3 |
| Secure, user-friendly, multi-method payments | 4, 9, 11 |
| Traveler feedback submission and visibility | 6, 9 |
| Manager travel-listing management | 2, 9 |
| Manager subscriber-list interaction | 3, 9 |
| Manager listing analytics | 8, 9 |
| Detailed Traveler profile/statistics | 8, 9 |
| Secure, straightforward login | 1, 9, 11 |
| Correct role-based access control | 1, 11 |
| TLS-secured transmission | 11 |
| Secure credentials and sensitive data | 4, 11 |
| High traffic and actions under five seconds | 5, 10 |
| Service-failure fallback | 5, 7, 10 |
| Responsive UI and easy role navigation | 9 |
| Data-protection/privacy compliance | 11 |
| Readable, well-separated code | 0, each review, 13 |
| SQL injection and XSS protection | 6, 11 |
| Password encryption/hashing | 1, 11 |
| PWA bonus | Bonus A |
| Multilingual bonus | Bonus B |
| Innovative-feature bonus | Bonus C |

## Completion log

Add one row only after a phase completion gate passes.

| Phase | Date | Commit/PR | Verification commands and evidence | Notes |
| --- | --- | --- | --- | --- |
| Phase 0 |  |  |  |  |
| Phase 1 |  |  |  |  |
| Phase 2 |  |  |  |  |
| Phase 3 |  |  |  |  |
| Phase 4 |  |  |  |  |
| Phase 5 |  |  |  |  |
| Phase 6 |  |  |  |  |
| Phase 7 |  |  |  |  |
| Phase 8 |  |  |  |  |
| Phase 9 |  |  |  |  |
| Phase 10 |  |  |  |  |
| Phase 11 |  |  |  |  |
| Phase 12 |  |  |  |  |
| Phase 13 |  |  |  |  |

