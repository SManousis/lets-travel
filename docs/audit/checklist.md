# Final audit checklist

This checklist maps the upstream exercise requirements (microservices, PostgreSQL/Neo4j, Jenkins/SonarQube, Docker/Ansible, logging, admin CRUD, Stripe/PayPal, authentication, responsive Chrome/Firefox UI, tests, TLS, Vault, least privilege, and PR review) to local evidence. Follow the [manual testing guide](manual-testing-guide.md) for the order, commands, expected outcomes, and results template.

| Area | Demonstration | Evidence |
|---|---|---|
| Architecture and ownership | Read service map and data model | `docs/architecture.md`, `docs/data-model.md` |
| Databases | `docker compose ... config`; inspect Flyway/Cypher migrations | `infra/postgres`, `infra/neo4j`, `docs/data-model.md` |
| Security | `scripts/audit/final-rehearsal.sh`; secure overlay and TLS curl | `docs/security.md`, `docs/audit/security-rehearsal.md` |
| CRUD and lifecycle | Authenticated gateway CRUD, invalid/duplicate/missing cases | `docs/api/*.yaml`, service tests, `docs/audit/evidence.md` |
| Payments | Stripe SetupIntent and PayPal vault reference handoff | `docs/payments.md`, payment adapters |
| UI | `cd admin-ui && npm run build`; Chrome/Firefox responsive walkthrough | `admin-ui`, `docs/audit/browser-checks.md` |
| CI/CD | Jenkinsfile main-branch quality gate and Ansible deploy | `Jenkinsfile`, `docs/deployment.md` |
| Scale and recovery | Scale compose and run replica smoke script | `scripts/load/replica-smoke.sh`, `docs/audit/resilience.md` |
| Tracing | Send a fixed `X-Request-ID` and inspect logs/collector | `docs/audit/tracing.md` |
