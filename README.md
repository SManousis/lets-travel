# Let's Travel

A group-travel platform: travel managers publish trips, travelers find them with full-text search and
personal recommendations, subscribe and pay, cancel up to three days before departure, and rate trips afterwards;
administrators oversee everything through dashboards and moderation.

It runs as independent Spring Boot services behind an API gateway, with a React web app, PostgreSQL, Neo4j and
Elasticsearch, verified in Jenkins with SonarQube and deployed with Ansible.

| Service | Owns | Store | Port (app / management) |
| --- | --- | --- | --- |
| api-gateway | Routing, token and role checks, rate limits | — | 8080 / 9080 |
| auth-service | Credentials, sessions, tokens | PostgreSQL `travel_auth` | 8081 / 9081 |
| user-service | Accounts, roles, traveler profiles, account deletion | PostgreSQL `travel_users` | 8082 / 9082 |
| travel-service (`lets-travel/`) | Travels, itineraries, capacity, recommendations | Neo4j | 8083 / 9083 |
| payment-service | Payment methods, payments, refunds | PostgreSQL `travel_payments` | 8084 / 9084 |
| engagement-service | Subscriptions, feedback, reports, dashboards, event outbox | PostgreSQL `travel_engagement` | 8085 / 9085 |
| search-service | Search and autocomplete index | Elasticsearch | 8086 / 9086 |
| admin-ui | Web app for all three roles | — | 5173 (container 8080) |

## Prerequisites

- Docker with Compose v2 (Docker Desktop on Windows/macOS); about 8 GB of memory for the full stack.
- JDK 21 and Node.js 22.12+ with npm 10+ to build and test. Maven comes with the wrapper (`./mvnw`, `mvnw.cmd`).
- Internet access on the first build.

## Run it locally

```sh
cp .env.example .env                      # then replace the values (never commit .env)
./mvnw -B -ntp -DskipTests package        # builds every service jar
docker compose --env-file .env -f infra/compose/compose.yml up -d --build
```

Open <http://127.0.0.1:5173> and sign in with `ADMIN_USERNAME` / `ADMIN_PASSWORD` from `.env`. The first start
takes a few minutes (Elasticsearch and Neo4j initialise); `docker compose -f infra/compose/compose.yml ps` shows when
every service is `healthy`.

- **HTTPS, Vault and database TLS** (as deployed): generate local certificates with `infra/tls/generate-dev-ca.sh`
  and add `-f infra/compose/compose.secure.yml`; the app is then at <https://localhost:8443>.
- **Two replicas of every service:** add `-f infra/compose/compose.scale.yml` (see [operations](docs/operations.md#scaling-and-its-limits)).
- **Stop:** `docker compose -f infra/compose/compose.yml down` (data stays in volumes). **Reset everything:** add `-v`.

### Demo data

```sh
DEMO_PASSWORD='choose-a-password' ADMIN_EMAIL=admin@example.test ADMIN_PASSWORD=... \
  BASE_URL=http://127.0.0.1:5173 node scripts/demo/seed.mjs
```

Creates two travel managers, two travelers, destinations and six published travels of different kinds, with
bookings paid through the test provider, all under `@example.test` addresses and the password you pass. Running it
again changes nothing. Details in [the demo guide](docs/demo.md).

## Build and test

```sh
./mvnw -B -ntp clean verify               # all backend tests (Elasticsearch tests start a container)
npm ci && npm run lint:api                # OpenAPI contracts
cd admin-ui && npm ci && npm test && npm run lint && npm run build
./mvnw -pl engagement-service -am verify  # one service and what it depends on
```

The same checks plus security scans, SonarQube and deployment run in Jenkins: see [CI](docs/ci.md).

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| A service stays `starting`, then restarts | Its database is still starting; services retry and recover on their own. Check `docker compose logs <service>`. |
| Search says "temporarily limited" | Elasticsearch is down or still starting; browsing still works and search returns on its own ([operations](docs/operations.md#fallbacks-when-a-dependency-fails)). |
| `401` everywhere after a restart | Sessions are in auth-service; sign in again. Changing `AUTH_TOKEN_SECRET` signs everyone out. |
| `429 RATE_LIMITED` | Too many searches, reports or sign-ups in a short time ([security](docs/security.md#2-input-handling-and-injection)). |
| Payments have no methods to choose | Set `PAYMENT_TEST_PROVIDER_ENABLED=true` for the test card, or configure a sandbox provider ([payments](docs/payments.md)). |
| Elasticsearch exits at start | Give Docker more memory, or on Linux set `vm.max_map_count=262144`. |
| Tests time out on a busy machine | The Elasticsearch integration test needs a few free cores; run module tests with `-pl <module> -am`. |

## Documentation

| Question | Where |
| --- | --- |
| How the services fit together, who owns what | [architecture](docs/architecture.md), [data model](docs/data-model.md) |
| What each role can do | [roles and permissions](docs/roles.md) |
| How search and autocomplete use Elasticsearch | [search](docs/search.md) |
| How Neo4j produces personal recommendations | [recommendations](docs/recommendations.md) |
| How data stays consistent across PostgreSQL, Neo4j and Elasticsearch | [events and consistency](docs/events.md) |
| How services scale and behave under failure and load | [operations](docs/operations.md) |
| Subscriptions, the three-day cutoff, payments, feedback | [subscriptions](docs/subscriptions.md), [payments](docs/payments.md), [feedback and reports](docs/feedback-and-reports.md) |
| Dashboards and their rules | [dashboards](docs/dashboards.md) |
| Security and privacy | [security](docs/security.md), [privacy](docs/privacy.md) |
| CI, deployment and rollback | [CI](docs/ci.md), [deployment](docs/deployment.md) |
| API contracts | [docs/api](docs/api/README.md) |
| Library choices and versions | [versions](docs/versions.md) |
| Audit: every question with its evidence | [final audit](docs/audit/final-audit.md) |

## Conventions

Each service is its own Maven module and container, shares only `service-common` (security, tracing, resilience,
audit helpers), and never reads another service's database. Java packages are `com.travelplan.<service>`; tests
mirror them under `src/test/java`. Work happens on `feat/…` or `fix/…` branches and reaches `main` through reviewed
pull requests that pass every CI gate.
