# Versions and package choices

Baseline selected on 2026-09-11. Active build dependencies are pinned in Maven
POMs and npm package/lockfiles. Upgrade them deliberately, rerun verification, and
update this document; do not use `latest` in deployment configuration.

## Phase 1 build

| Component | Pin / supported local runtime | Reason |
| --- | --- | --- |
| Java | Language/API level 21; verified with OpenJDK 21.0.12 | LTS Java baseline with records, modern HTTP client, and wide tooling support |
| Spring Boot | 4.1.1 | Application lifecycle, HTTP server, configuration and managed dependency versions |
| Spring Cloud | 2025.1.3 BOM | Compatible Gateway dependency set for Boot 4.1 |
| Maven / wrapper | 3.9.16 / 3.3.4 | Repeatable build without a global Maven installation; official Apache wrapper scripts |
| Node.js / npm | Node 22 >=22.12; local verification uses 22.16.0 / npm 11.4.2 | Supported Vite runtime; npm lockfiles make dependency installation reproducible |
| React / React DOM | 19.3.0 | Component model for the admin UI |
| TypeScript | 7.0.2 | Compile-time checks for browser code |
| Vite / React plugin | 8.3.0 / 6.1.1 | Development server, API proxy and production asset build |
| Redocly CLI | 2.52.0 | OpenAPI structural, reference and consistency linting |

Boot 4.1 supports Java 21 and Maven 3.9. Spring Cloud's compatibility table maps
2025.1 to Boot 4.0/4.1 (4.1 support starts at Cloud 2025.1.2). These selections use
the compatible service release rather than mixing independent Spring component
versions. Sources: [Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Cloud compatibility](https://spring.io/projects/spring-cloud/).

Vite requires Node 20.19+ or 22.12+; the selected React plugin declares Vite 8
compatibility. Node 22 is the project baseline, including the root contract tools.
Package versions/engines were checked against the npm registry during setup.
Sources: [Vite requirements](https://vite.dev/guide/),
[React package](https://www.npmjs.com/package/react),
[Vite React plugin](https://www.npmjs.com/package/@vitejs/plugin-react),
[Redocly CLI](https://redocly.com/docs/cli).

The business services use `spring-boot-starter-webmvc`; the gateway uses
`spring-cloud-starter-gateway-server-webflux` without the servlet starter.
Actuator supplies private health/probe endpoints. `spring-boot-starter-test`
provides JUnit and AssertJ; tests use the JDK HTTP client and small local upstreams
to exercise our routing/configuration boundaries. Boot's BOM also manages Jackson,
logging, server, and test-library versions; no independent overrides are needed.
On Apple Silicon, a Maven OS profile adds the BOM-managed native macOS DNS
resolver so Netty does not try to load an Intel-only native library. Linux builds
do not activate this development-host dependency.

Database drivers, persistence frameworks, Spring Security, provider SDKs, Vault
clients, and telemetry exporters will be added with their consuming features.
Adding them now would require unused infrastructure and could obscure whether a
skeleton starts independently. Use the Boot/Cloud BOMs for those future Java
dependencies where they provide a compatible version.

Each Java runtime Dockerfile pins `eclipse-temurin:21.0.12_8-jre-alpine` and copies
the verified executable jar. UI build/runtime image pins live in
`admin-ui/Dockerfile`. Images run as non-root users. Image execution is a separate
check from the successful host builds; the Docker daemon was unavailable during
initial Phase 1 verification.

## Later infrastructure baseline

These are selected target versions, not installed or integration-tested services.
Recheck security patches, supported combinations, image availability and immutable
digests when introducing each component in its implementation phase. This table
records the architecture baseline without claiming an unbuilt stack is verified.

| Component | Selected baseline | Purpose / compatibility decision |
| --- | --- | --- |
| PostgreSQL | 17.11 | Separate owned relational databases for auth, users and payments; use a separate database/identity for CI tools |
| Neo4j | 2026.08.1 Community | Travel graph transactions and relationships; verify the Boot-managed driver's server compatibility in Phase 2 |
| Docker Engine | 29.8.0 | Linux deployment runtime; local Phase 1 host builds do not require Docker |
| Docker Compose | 5.5.1 | Declarative local/deployment service definitions using the Compose specification |
| Ansible Core | 2.21 release line, Python 3.12–3.14 controller | Repeatable provisioning; pin the then-current patch and collections in Phase 10 |
| Jenkins | 2.568.3 LTS, JDK 21 | Reviewed build/test/deploy workflow |
| SonarQube Community Build | 26.9.0.129388 | Quality/security analysis; Phase 3 must demonstrate a supported per-PR analysis approach for this edition |
| Vault | 2.1.0 | Runtime secret storage and per-service policies; introduce against its current API in Phase 4 |

Release/support references: [PostgreSQL releases](https://www.postgresql.org/docs/release/),
[Neo4j releases](https://neo4j.com/release-notes/),
[Docker Engine](https://docs.docker.com/engine/release-notes/29/),
[Compose](https://github.com/docker/compose/releases),
[Ansible/Python support](https://docs.ansible.com/projects/ansible-core/devel/reference_appendices/release_and_maintenance.html),
[Jenkins LTS](https://www.jenkins.io/changelog-stable/),
[SonarQube downloads](https://www.sonarsource.com/products/sonarqube/downloads/),
[Vault releases](https://releases.hashicorp.com/vault/).

PostgreSQL/Neo4j are owned persistence stores, not shared integration buses.
Jenkins/SonarQube are required by the exercise. Vault is the selected secret store.
OpenTelemetry is the intended tracing protocol; choose and pin its collectors and
storage backend in Phase 9 when the actual logging requirements can be tested.
Kubernetes remains an optional bonus and is not a Phase 1 dependency.
