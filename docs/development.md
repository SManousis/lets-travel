# Development and CI

Run `./mvnw -B -ntp verify` for the backend, `npm ci && npm run lint:api` for API contracts, and `npm --prefix admin-ui ci && npm --prefix admin-ui test && npm --prefix admin-ui run lint && npm --prefix admin-ui run build` for the dashboard. On Windows, use `mvnw.cmd`.

`Jenkinsfile` runs backend, API, dashboard, dependency advisory, Ansible playbook, SonarQube analysis, and quality-gate checks for every branch and PR it builds and publishes dashboard unit test results alongside backend JUnit results. On `main` it also publishes SHA-tagged images, and invokes Ansible. The stages fail when required tools, registry settings, credentials, or deployment inventory are missing. See [release deployment](deployment.md) for agent and target setup.

Dependency review runs `npm audit --audit-level=high` for the root tooling and `admin-ui` lockfiles on every CI build. For a dependency update, commit the changed lockfile, run the backend verification, dashboard unit/lint/build checks, API lint, and both npm audits, then record the advisory and version decision in the PR. Address high/critical findings before merge; if no compatible fix exists, record the affected package, exposure, mitigation, owner, and review date in the PR instead of silently suppressing the result. Review Java dependencies and container base images separately during the release security review; the npm audit stage does not cover them.

```sh
docker compose -f infra/compose/compose.ci.yml up -d
```

The local CI stack binds Jenkins and SonarQube to loopback and keeps data in named volumes. It supplies the services, not a configured Jenkins agent, plugins, credentials, or a demonstrated quality gate. SonarQube Community Build keeps one branch per project, so PR and `main` analyses share the `travel-plan` project and the dashboard shows the latest one. Protect `main` in Gitea with the PR validation job and record a failing-check rejection before marking Phase 3 complete.
