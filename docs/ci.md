# Continuous integration and deployment

How a change is verified, what blocks a merge, and how a release is deployed and rolled back. The pipeline is
`Jenkinsfile`; the local CI stack (Jenkins, SonarQube, registry, agent, deploy host) is described in
[deployment](deployment.md).

## 1. Pipeline

Every branch and pull request runs the verification stages; `main` also publishes, deploys and smoke-tests.

| Stage | What it checks | Blocks on |
| --- | --- | --- |
| Toolchain | Java, Node, Sonar scanner, gitleaks, osv-scanner, Trivy are present | missing tool |
| Backend verification | `./mvnw clean verify`: unit, API, matrix and integration tests (H2, embedded Neo4j, Elasticsearch Testcontainers), Maven Enforcer dependency rules, JaCoCo | any failing test or banned dependency |
| API contracts | Redocly lint of every OpenAPI file | lint error |
| Dashboard tests, lint and build | Vitest with coverage, oxlint, TypeScript build | failure |
| Dependency advisories | `npm audit --audit-level=high` (root and UI) | high or critical advisory |
| Security scans | gitleaks over the git history; CycloneDX SBOM of all Java services + osv-scanner over it and both npm lockfiles | any secret; any known vulnerability not accepted in `osv-scanner.toml` |
| Deployment playbook checks | Ansible syntax check and ansible-lint (production profile) | failure |
| SonarQube analysis + Quality gate | Sonar way gate on new code (bugs, vulnerabilities, hotspots, coverage, duplication) | gate failed |
| Publish release images (main) | Builds every image with the commit as tag and OCI revision label; Trivy scan; refuses to overwrite a published tag with different content; pushes; writes `release-manifest.json` | fixable HIGH/CRITICAL image vulnerability; tag conflict |
| Deploy reviewed main (main) | Ansible `deploy.yml` with the exact commit tag (below) | failed rollout, after rolling back |
| Post-deploy smoke (main) | `scripts/load/load.mjs` search scenario, 2 users, 15 s, p99 < 5 s | slow or failing search |

Published with every build: JUnit results (backend and UI), JaCoCo and Vitest coverage, the SBOM, scan reports
(`reports/security/`), the smoke-test report (`reports/smoke/`), the release manifest and the built artifacts.

Not automated yet: browser E2E suites (the journeys in [browser checks](audit/browser-checks.md) are run by hand),
and the load and failure drills of [operations](operations.md#load-tests), which need a quiet machine.

## 2. Accepting a finding

- **Secret scan:** a confirmed false positive goes into `.gitleaksignore` (its fingerprint) with the reason in
  [security](security.md#7-scans-and-triage). A real secret is rotated first, then removed from history.
- **Dependency vulnerability:** upgrade if a fix exists (a version property in the root `pom.xml` or the npm
  lockfile). If it cannot be fixed or does not apply, add an `[[IgnoredVulns]]` entry with `ignoreUntil` and the
  reason to `osv-scanner.toml`, and record the decision in [security](security.md#7-scans-and-triage).
- **Image vulnerability:** rebuild on a patched base image; the gate ignores vulnerabilities that have no fix yet.

## 3. Merging

Rules for `main` (set in Gitea under *Settings → Branches → Protected branches*):

1. No direct pushes; changes arrive through pull requests from `feat/…` or `fix/…` branches with a descriptive
   title and description.
2. The Jenkins status check that the Gitea plugin reports on the pull request must pass ("Enable Status Check"
   with the Jenkins check selected); Gitea blocks the merge otherwise.
3. At least one approving review from another team member. (On the Zone01 Gitea, approvals may be limited for
   the repository's accounts; in that case the review is a PR comment and is recorded in the evidence below.)
4. Merge commits are used, so each phase stays visible in the history.

### Evidence that the gates block

Capture once, then remove:

1. Open a PR that adds a failing assertion to any test. Record the red Jenkins check and Gitea's blocked merge
   button (screenshot and build URL).
2. Open a PR that adds an obvious Sonar violation (for example an unused private method with an empty catch).
   Record the failed quality gate.
3. Close both PRs without merging and note them in [audit evidence](audit/evidence.md).

## 4. Releases and deployment

- **Immutable images.** Each service image is tagged with the full commit SHA and carries it as its
  `org.opencontainers.image.revision` label. A tag is never rebuilt with different content, so a SHA always means
  the same bits. `release-manifest.json` maps the commit to every image digest.
- **Deploy.** `infra/ansible/deploy.yml` validates the release input and secrets, copies the manifests, renders the
  private environment file, creates missing databases and roles, then runs `rollout.yml`:
  1. data stores (PostgreSQL, Neo4j, Elasticsearch, Vault, telemetry) are started or kept running;
  2. application services are replaced **one at a time**, each healthy before the next (auth, users, travel,
     payments, engagement, search, gateway, UI, proxy);
  3. the HTTPS entry point must serve the UI and route the API.
- **Migrations.** Flyway runs each service's migrations at start-up. Migrations only add (new tables, new nullable
  columns, new indexes); removing or renaming something takes two releases (expand, then contract once no
  running release uses the old shape). That is what makes rollback safe.
- **Rollback.** The playbook remembers the running release (`/opt/lets-travel/current-release`). If the rollout or
  the health checks fail, it rolls the previous release back out the same way and fails the build with
  "Rolled back to …". Data volumes are never touched. `/opt/lets-travel/release-history` lists every deployment
  and rollback.
- **Manual rollback.** Run the deploy for an older commit: `ansible-playbook … deploy.yml -e release_tag=<sha> …`
  (or replay that commit's Jenkins build). Its images are still in the registry.

### Deployment rehearsal

Record each in [audit evidence](audit/evidence.md) with the build URL:

| Rehearsal | How |
| --- | --- |
| Clean deployment | Remove the deploy host's containers and volumes, run the `main` build |
| Repeat deployment | Re-run the same build: nothing is recreated, health checks pass |
| Safe migration | Deploy a release that adds a migration; check the data from before is still there |
| Rolling update | Watch `docker ps` during a deploy: one application container is replaced at a time |
| Failed-health rollback | Deploy a commit whose gateway cannot start (for example a broken route); the build fails with "Rolled back to …" and the previous release serves again |
| Persistent data | Count users, travels and subscriptions before and after each of the above |
