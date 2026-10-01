# Travel Plan

Travel management administration built as four independent business services,
an API gateway, and a React dashboard.

Phase 1 provides runnable service skeletons, private health endpoints, tested
gateway routing, a UI shell, and API/data contracts. Business CRUD, authentication,
databases, payment integrations, and deployment infrastructure are scheduled in
[plan.md](plan.md). The documented business APIs are not implemented yet; requests
to a running domain service currently return `404`.

## Prerequisites

- JDK 21 (set `JAVA_HOME` if Java is not on your path).
- Node.js 22, at least 22.12, and npm 10 or newer.
- Internet access on the first build to download dependencies. Maven 3.9.16 is
  downloaded by the included Apache Maven Wrapper; no global Maven is required.

Exact dependency pins, compatibility sources, and later infrastructure selections
are in [docs/versions.md](docs/versions.md). No database or Docker installation is
needed to build and run the Phase 1 applications locally.

## Build and verify

From the repository root:

```sh
./mvnw verify
npm ci
npm run lint:api
npm --prefix admin-ui ci
npm --prefix admin-ui run build
```

On Windows, use `mvnw.cmd verify`. Each Java module produces an executable
`target/<module-name>.jar`. The UI build produces `admin-ui/dist/`. Root npm tooling
validates API contracts; frontend dependencies have their own package and lockfile.

Backend checks start each application on temporary ports, exercise health and
management isolation, and send real requests through the gateway to four local
test upstreams. They verify domain routing, path/query/method/header/body
preservation, and rejection of unmatched/private routes. These small scaffold
checks do not replace the feature unit tests required in later phases.

Build/test a single module without rebuilding the others:

```sh
./mvnw -pl user-service -am verify
```

## Run locally

After the build, start each command in a separate terminal:

```sh
java -jar auth-service/target/auth-service.jar
java -jar user-service/target/user-service.jar
java -jar travel-service/target/travel-service.jar
java -jar payment-service/target/payment-service.jar
java -jar api-gateway/target/api-gateway.jar
npm --prefix admin-ui run dev
```

Open <http://127.0.0.1:5173>. The development server proxies `/api` to the gateway.
Stop each process with Ctrl+C. To run directly from Maven during development:

```sh
./mvnw -pl user-service spring-boot:run
```

| Application | Application port | Management port |
| --- | --- | --- |
| Gateway | 8080 | 9080 |
| Authentication | 8081 | 9081 |
| Users and travelers | 8082 | 9082 |
| Travel and itineraries | 8083 | 9083 |
| Payment management | 8084 | 9084 |

Both listeners bind to `127.0.0.1` by default. Health is available only on the
management listener, for example:

```sh
curl --fail http://127.0.0.1:9082/actuator/health
curl --fail http://127.0.0.1:9080/actuator/health/readiness
```

Both report `"status":"UP"`; the root health response also lists probe groups. `/actuator/health` on an application port returns
`404`. Only health (including readiness/liveness) is exposed on management ports;
environment/configuration endpoints are not exposed. An `UP` result in this phase
means the skeleton has started, not that future database/provider dependencies work.

Each application accepts `SERVER_PORT`, `SERVER_ADDRESS`, `MANAGEMENT_PORT`, and
`MANAGEMENT_ADDRESS`. Set both ports when running another local instance. Gateway
targets accept `AUTH_SERVICE_URL`, `USER_SERVICE_URL`, `TRAVEL_SERVICE_URL`, and
`PAYMENT_SERVICE_URL`; defaults are the loopback application addresses above.
The full `/api` prefix is preserved when forwarding.

This skeleton uses local HTTP and has no auth implementation. Keep the loopback
defaults for local development. Phase 4 supplies authentication and verified TLS
before business functionality is exposed outside the development environment.

## Container build contexts

Each backend directory is an independent runtime-image context. Build its verified
jar first, then build its image:

```sh
./mvnw -pl user-service -am verify
docker build -t travel-plan/user-service:phase-1 user-service
docker run --rm -p 127.0.0.1:8082:8082 travel-plan/user-service:phase-1
```

Repeat with the other module names. Images run as a non-root user, listen on their
application port inside the container, and keep management bound to loopback there.
The frontend context builds its assets in its Dockerfile:

```sh
docker build -t travel-plan/admin-ui:phase-1 admin-ui
docker run --rm -p 127.0.0.1:5173:8080 travel-plan/admin-ui:phase-1
```

Docker Compose, container networking, database persistence, container health
checks, and deployment profiles belong to Phase 2. A gateway container will need
explicit service URLs on a shared network; its local defaults are for host-run
services. The UI runtime image serves the shell; its `/api` response is `503`
until deployment supplies gateway routing.

## Design and development

- [Architecture and lifecycle rules](docs/architecture.md)
- [PostgreSQL ownership and Neo4j graph](docs/data-model.md)
- [API conventions and OpenAPI contracts](docs/api/README.md)
- [Version pins and package choices](docs/versions.md)
- [Phase checklist](plan.md)

Use descriptive feature branches and reviewed PRs. Java packages use
`com.travelplan.<domain>`; keep future feature code together inside its service and
mirror it under `src/test/java`. Use PascalCase for Java classes/React components
and camelCase for methods, variables, and JSON fields. Services share dependency
management but no domain classes or direct database access. The React app remains
independent of the Maven reactor.
