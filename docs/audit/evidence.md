# Audit evidence record

Final review source: 01-edu Travel-Plan exercise README, reviewed 2026-09-16. The implementation uses Java 21/Spring Boot 4, Docker Compose, PostgreSQL 17, Neo4j 2026, React/Vite, Jenkins, SonarQube, Ansible, Vault, and an OTLP collector. Use the [manual testing guide](manual-testing-guide.md) to record final PASS/FAIL/BLOCKED evidence for the current branch.

Run `scripts/audit/final-rehearsal.sh` from the repository root. It validates clean Compose configuration, backend packaging, UI production output, unauthenticated denial, and repository secret scanning. Runtime evidence is collected with the security, tracing, resilience, and browser walkthroughs; each explicitly records the single-host/database-HA limitations.

No raw payment credentials are accepted or persisted. Test defaults are documented placeholders and must be replaced through environment/Vault for a deployment.
