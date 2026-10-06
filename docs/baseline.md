# Phase 0 baseline and inventory

Recorded on 2026-10-06 against `main` at `5b3864c` (merge of PR #2). This is the
starting point for the Let's Travel work: what passes, what the inherited Travel
Plan code really does, and what is only documented or configured.

## Baseline checks

All checks ran in the Jenkins pipeline (`lets-travel` job, `main` build #4) unless
noted. Every check passed.

| Check | Command or stage | Result |
| --- | --- | --- |
| Backend build and tests | `./mvnw -B -ntp verify` | 9 modules, 102 tests, 0 failures; JaCoCo report per module |
| Service boundaries | Maven Enforcer `service-boundaries` | Passes; a deliberate cross-service dependency fails the build (checked locally) |
| API contracts | `npm run lint:api` | 4 contracts valid at `5b3864c`; 8 after this PR |
| Dashboard | `admin-ui`: `npm run test:ci`, `lint`, `build` | 8 tests pass; lint clean; production build succeeds |
| Dependency advisories | `npm audit --audit-level=high` (root and `admin-ui`) | 0 vulnerabilities |
| Deployment playbooks | `ansible-playbook --syntax-check`, `ansible-lint` | 0 failures, 0 warnings |
| Code quality | SonarQube analysis and quality gate (Sonar way) | Gate `OK` |
| Compose configuration | `docker compose config -q` for base, release+secure, scale | All resolve (checked locally) |
| Release | Build and push 8 images to the CI registry, Ansible deploy | 23 tasks ok, 0 failed; 14 containers running, all health checks healthy |
| Live deployment | `https://localhost:8443/` and `/api/users` without a token | `200` and `401` |

The pipeline repeats all of these on every pull request (except publish and
deploy, which run only on `main`), so this table is also the regression baseline.

## Inventory of inherited behaviour

The repository started as a copy of Travel Plan, an admin-only system. Each row
below was checked in the code, not taken from earlier documentation.

### Implemented and tested

| Area | What exists | Evidence |
| --- | --- | --- |
| Login and sessions | Login with BCrypt password hashes in PostgreSQL; signed access tokens checked for signature, issuer, audience and expiry; logout and revocation; auth-service validates tokens for other services | `auth-service` tests (21) |
| Users and travelers | Admin CRUD for users and traveler profiles in PostgreSQL; idempotent user creation; deletion cleans up participation and payment methods across services | `user-service` tests (12) |
| Travels | Admin CRUD for travels and shared destinations in Neo4j, with ordered stops, activities, accommodation, transport and participants; itinerary validation | `travel-service` tests (12), including an in-process Neo4j repository test |
| Payment methods | Admin CRUD for payment gateways and methods in PostgreSQL; one default method per user; Stripe and PayPal sandbox adapters with a local provider simulator | `payment-service` tests (14), simulator tests in `scripts/sandbox` |
| Gateway | Routes `/api/auth`, `/api/users`, `/api/travelers`, `/api/travels`, `/api/destinations`, `/api/payment-*`; rejects non-admin tokens; adds request IDs | `api-gateway` tests (24) |
| Dashboard | React admin screens for users, travels with an itinerary editor, and payments; login and session expiry | `admin-ui` tests (8) |
| Request tracing | Structured request log line with trace and instance IDs in every service; tokens never logged | `service-common` tests (9) |
| Delivery | Jenkins pipeline, SonarQube gate, private registry, Ansible deploy to an HTTPS target | Baseline checks above |

### Partial or different from the documentation

| Area | Finding |
| --- | --- |
| Roles | Only `ADMIN` can use the system. A legacy `OPERATOR` role exists in user and auth code but grants nothing. `TRAVEL_MANAGER` and `TRAVELER` do not exist (Phase 1). |
| Token format | Tokens are a custom HMAC-signed format (`payload.signature`), not JWT, although the contracts say `bearerFormat: JWT`. Phase 1 either adopts JWT or corrects the contracts. |
| Refresh | The refresh cookie holds the access token itself, and `/api/auth/refresh` accepts it as a refresh token. There is no separate rotating refresh token or CSRF check yet, unlike [auth.yaml](api/auth.yaml). Fix in Phase 1. |
| Travel model | A travel has no manager, price, currency, capacity or publication state, so nothing can be sold, browsed by travelers or owned by a manager (Phase 2). |
| Cross-service writes | User deletion returns `202` when cleanup is pending, but there is no operation resource to poll, unlike [architecture](architecture.md#lifecycle-orchestration). |
| Payment providers | Stripe and PayPal adapters call the real sandbox APIs when credentials are set; this has not been verified with real sandbox credentials. Without them, only the simulator path is tested. |

### Configured or documented only

| Area | Finding |
| --- | --- |
| Vault | Runs in the secure deployment with per-service policy templates, but no service reads secrets from it; secrets come from environment variables. |
| Telemetry | The OpenTelemetry collector runs, but no service exports traces or metrics to it. |
| Internal TLS | PostgreSQL and Neo4j accept TLS in the secure deployment, and the public entry point is HTTPS. Service-to-database connections do not require TLS, and Elasticsearch HTTP is plain inside the private network (Phase 11). |
| Replication and failover | Services can run two replicas (`compose.scale.yml`); databases are single instances. No failover is claimed. |

### Not started (Let's Travel scope)

Subscriptions and the 3-day cutoff, subscription payments, Elasticsearch search
and autocomplete, feedback, reports and moderation, Neo4j recommendations,
manager and admin dashboards, traveler-facing screens, role-aware navigation,
PWA and multilingual support. Contracts and data flow for these are defined in
[docs/api](api/README.md), [events](events.md) and [architecture](architecture.md);
the services `engagement-service` and `search-service` exist with health checks
and storage, but no business endpoints yet.
