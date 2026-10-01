# Phase 1 verification

Verified on 2026-09-11 on branch `feat/phase-1-foundation` with JDK 21.0.12,
Node 22.16.0, npm 11.4.2, and Maven Wrapper 3.9.16.

| Check | Result |
| --- | --- |
| `./mvnw -B -ntp verify` | PASS: all five modules packaged; 23 tests, zero failures/errors/skips |
| `npm ci` and `npm run lint:api` | PASS: four OpenAPI 3.0.3 documents validated, no lint errors or warnings |
| `npm --prefix admin-ui run build` | PASS: TypeScript check and Vite production assets |
| `npm --prefix admin-ui run typecheck` | PASS |
| UI development server | Starts on loopback port 5173 and serves the app HTML |
| Docker base image metadata | Corrected Java pin to `21.0.12_8-jre-alpine`; verified this, `node:22.16.0-alpine`, and `nginx:1.28.0-alpine` exist in the registry |
| Docker image execution | Not verified: Docker daemon unavailable on this host |

The root npm clean install was also reproduced offline from the populated cache
using `npm ci --ignore-scripts --offline --no-fund --cache /tmp/travel-plan-npm-cache`.
Initial Maven/npm downloads required network/cache access outside the workspace
sandbox. The final Maven command uses the standard user dependency cache.

## Coverage and review

Each backend application has two startup checks: live health/probe responses on
its separate management listener, and isolation of health/environment/internal
paths from public routing. Gateway tests add nine routing cases across all four
domain services and four negative routing cases. They assert real HTTP method,
path, query, custom header, body, and owning upstream, without database dependencies.

Before routes were added, all nine forwarding cases failed with `404` instead of
`200`. After configuring the routes, all passed. The health test was adjusted to
read the JSON status and absence of detailed components rather than assuming the
framework never adds probe-group metadata. The Apple Silicon resolver profile
removed Netty's native macOS DNS load error on this host.

A separate review checked Phase 1 requirements, contracts, build files, tests,
UI, and documentation. Its only important finding was the Java container tag;
the registry rejected the original `_7` tag and confirmed the corrected `_8` tag.
No business implementation or database availability is implied by these checks.

## Remaining observations

- The test runtime emits JDK dynamic-agent-loading warnings from the starter's
  Mockito/Byte Buddy test support. No mock-based business tests exist yet.
- Spring Cloud Gateway metadata emits Hibernate Validator `HV000271` warnings
  about upstream container-level `@Valid` annotations. The compatible Boot/Cloud
  build passes; no warnings were hidden or framework versions overridden. Review
  an upstream fix and test-agent configuration with Phase 3's quality pipeline.
- Container build/run, databases, TLS, authentication and admin CRUD are work for
  the later phases; only the container build contexts and contracts exist here.
- Work remains on the feature branch for human PR review; nothing was merged or
  published as part of Phase 1 implementation.
