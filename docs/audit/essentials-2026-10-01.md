# Essential implementation check — 2026-10-01

Branch: `build-security-data-fixes`. This records local checks on the unmerged branch. It is not evidence of a configured Jenkins job or a deployed release.

## Changes

- Jenkins now runs dashboard unit tests with JUnit output, npm high-severity advisory checks for both lockfiles, and Ansible playbook syntax/lint checks on branch builds.
- `site.yml` prepares a fresh Debian/Ubuntu release host with Docker Engine and Compose before invoking the existing release playbook. Jenkins continues to use `deploy.yml` for repeat releases on prepared hosts.
- The dashboard has focused route/login, user-create retry, and payment-default tests. User creation retains its idempotency key for an unchanged retry. The payment screen displays and changes the default method, can request a default on connection, and locks pending connection inputs so retries keep the same key and reference.
- Payment methods have a local default preference, database backfill/constraint, atomic switching, and promotion when the default is removed. Provider-controlled fields remain immutable through the patch route.
- Request logs include a stable per-process instance ID; the optional collector shares the backend network. User-service startup now fails when loading persisted users fails.

## Local verification

| Check | Result |
| --- | --- |
| `mvnw.cmd -B -ntp verify` | Passed; 87 tests in the five service modules, zero failures/errors/skips. |
| `npm --prefix admin-ui run test:ci`, `lint`, `build` | Passed; eight dashboard tests and JUnit XML generated after the pending-form guard. |
| `npm run lint:api`, `npm run test:payment-sim` | Passed; four contracts valid and three simulator tests passed. |
| `npm audit --audit-level=high` in root and `admin-ui` | Both passed with zero reported vulnerabilities on the check date. |
| `ansible-playbook --syntax-check` for `site.yml` and `ansible-lint` for `site.yml`, `prepare.yml`, `deploy.yml` | Passed in a disposable Python 3.12 container with `ansible-core`, `ansible-lint`, and `community.docker`; zero lint findings. |
| Docker Compose release+secure and observability overlays | `docker compose config --quiet` passed with synthetic image coordinates. The resolved release has all six application images on the exact SHA tag and no build contexts. |
| Payment V1–V4 SQL | Applied in a disposable PostgreSQL 17.11 container; V4 backfilled one default for a user with two existing active methods. |
| Existing synthetic payment demo | Rebuilt the application images and reached healthy status. The persistent payment database reported Flyway versions 1–4 successful and two existing active methods with one default. An authenticated API PATCH switched the default, a fresh list showed exactly one selected method, and the original selection was restored. |

## Still open

The repository checks do not establish a live Jenkins/Gitea quality gate, SonarQube report, registry publication, or clean/repeat/rollback deployment. A configured agent, registry, TLS files, protected secrets, and Linux target are required for those checks. The payment default database path has local migration and API evidence, but no automated PostgreSQL transaction test in CI. Real Stripe/PayPal sandbox verification and provider ownership proof remain deferred until credentials and a trusted user-to-provider mapping exist. User creation still needs durable cross-database reconciliation. Collector export/viewing, full browser coverage, mutation audit records, and replica/failure evidence remain open.

The branch push was accepted and `git ls-remote` matched the local commit, but Gitea printed a nonblocking `fatal: ambiguous argument` message for its remote diff calculation. Inspect that server-side hook while configuring PR checks; this local push result does not prove PR status reporting works.
