# Manual testing guide

Use this guide on `build-security-data-fixes` before asking for a merge. It covers the checks that still need a person, a browser, configured CI, a release host, or real provider sandbox accounts. The [dated implementation record](essentials-2026-10-01.md) contains checks already run locally; repeat them on the final commit before claiming audit completion.

**Result rule:** Mark each check **PASS**, **FAIL**, or **BLOCKED**. PASS needs an observed result and evidence. BLOCKED means the required environment or credential is missing; it is not a pass. Work only with disposable test users and payment references. Never put passwords, bearer tokens, provider references, private keys, or unredacted environment files in screenshots, tickets, or this repository.

## 0. Prepare and record the run

Run commands from the repository root. Use PowerShell 7 for the local examples and a Linux Jenkins/Ansible agent for deployment commands.

```powershell
git branch --show-current
git rev-parse HEAD
git status --short
```

Record the branch, full 40-character SHA, date/time, tester, Docker/Jenkins/Sonar versions, browser versions, and environment in a copy of the results table at the end. Start with a clean working tree. Keep the current branch unmerged until the review is approved.

| Environment | Use it for | Required setup |
| --- | --- | --- |
| Local synthetic stack | Gateway, CRUD, dashboard, database persistence, request logs, and payment flow rehearsal | Docker, Node.js 22, the ignored `.env.payment-sim` file |
| CI and staging | Gitea PR gate, Jenkins, SonarQube, TLS, Ansible release and rollback | Configured Linux CI agent, protected credentials, image registry, reviewed staging inventory and certificates |
| Real provider sandbox | Stripe and PayPal reference/ownership proof | Provider-issued sandbox credentials and a trusted local-user-to-provider-customer mapping |

The local payment simulator uses fake credentials and **does not** prove Stripe or PayPal integration. Do not use the checked-in `infra/ansible/inventory.ini` as a production target; it points to localhost. See [deployment setup](../deployment.md) and [payment limits](../payments.md).

## 1. Start the local synthetic stack

If `.env.payment-sim` already exists, keep it; the generator refuses to overwrite it. If it does not exist, generate it:

```powershell
if (-not (Test-Path -LiteralPath '.env.payment-sim')) { node scripts/sandbox/create-sim-env.mjs }
docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml up -d --build --wait admin-ui
docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml ps
```

**Expected:** PostgreSQL, Neo4j, the simulator, four services, and gateway are healthy; the dashboard is running and returns HTTP `200`. Open `http://127.0.0.1:15173`. API is `http://127.0.0.1:18080`. Sign in as `sim-admin` using `ADMIN_PASSWORD` from your local ignored `.env.payment-sim` file; do not paste that value into evidence.

The two fake references are `pm_simulatedvisa001` (Stripe-shaped) and `VAULT_SIM_001` (PayPal-shaped). They are fixed fixtures. If the demo already has retained methods using them, inspect those records first. To reuse the references, delete the retained methods through the UI/API and restart `payment-simulator`; do not delete database volumes merely to reset a fixture. The optional `scripts/sandbox/smoke-payment-sim.ps1` creates and deletes methods automatically, but run it only when both references are available. See [simulator instructions](../payments.md#local-simulation-without-provider-accounts).

## 2. Gateway and authentication

Start with denial checks. In PowerShell, `curl.exe` avoids the `curl` alias:

```powershell
curl.exe -sS -o NUL -w "%{http_code}`n" http://127.0.0.1:18080/api/users
curl.exe -sS -o NUL -w "%{http_code}`n" -H "Authorization: Bearer invalid" http://127.0.0.1:18080/api/users
```

**Expected:** `401` for both. Then sign in with the dashboard. A valid admin can open users, travels, and payments; a wrong password cannot. Sign out and confirm the old session can no longer use protected pages. If you can issue an `OPERATOR` token through the approved operator credential process, confirm a business endpoint returns `403` for it. Do not mark the non-admin check PASS without that token.

For a direct API read and later log check, keep the token only in the current PowerShell process:

```powershell
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $PWD '.env.payment-sim'))) {
  $parts = $line.Split('=', 2)
  if ($parts.Length -eq 2) { $settings[$parts[0]] = $parts[1] }
}
$loginBody = @{ username = $settings.ADMIN_USERNAME; password = $settings.ADMIN_PASSWORD } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/auth/login' -Method Post -ContentType 'application/json' -Body $loginBody
$traceId = 'manual-users-' + [guid]::NewGuid().ToString('N')
$headers = @{ Authorization = "Bearer $($login.accessToken)"; 'X-Request-ID' = $traceId }
$response = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/api/users' -Headers $headers
$response.StatusCode
$response.Headers['X-Request-ID']
```

**Expected:** `200` and the same request ID. Keep `$headers` in memory for later checks; clear the terminal session afterward. On a TLS staging release, use the HTTPS hostname on the certificate and a trusted CA. Do not use `curl -k`/`--insecure` as TLS evidence. Confirm service and management ports are private and only intended entry ports are reachable. Use the [security checklist](security-rehearsal.md) for the logout/revocation sequence.

On staging, also review database roles, Vault policies, certificate validity/rotation, and whether protected values can be read only by their intended service. Record commands and redacted policy names. App-side Vault secret retrieval is not implemented yet, so mark that specific requirement BLOCKED rather than inferring it from the presence of Vault files.

## 3. Dashboard CRUD in Chrome and Firefox

Run the following at **1440 px, 768 px, and 390 px** viewport widths in both browsers. Use browser developer tools to set widths. Record the browser/version and a screenshot of each width with no horizontal clipping. Check keyboard Tab order, visible focus, labels, validation text, pending buttons, and readable list/cards. Use disposable records and note their UUIDs without personal data.

1. **Users/travelers:** Create an `OPERATOR` user; confirm it appears in the list and detail view. Edit its name and role/status, then refresh. Create a traveler for the active user, edit it, and confirm it remains after refresh. Try a duplicate email and missing/invalid fields: expect a visible controlled error with no duplicate. Do not delete the bootstrap admin.
2. **Destinations/travels:** Create two destinations, then a travel with ordered stops, dates, an activity, accommodation, transport, and the test traveler. Open detail and confirm the entire itinerary and participant persist. Edit the itinerary, remove/re-add the traveler, then refresh. Attempt to delete a destination still used by a travel: expect a conflict. **Leave this travel and participant in place** for the user-deletion check.
3. **Gateways/methods:** Create test Stripe and PayPal gateways. Connect each fake reference from section 1 to the disposable user. Confirm `ACTIVE`, safe display metadata, no raw provider reference in the response/list, exactly one default, **Make default** switching, label edit, and refresh persistence. While a connection returns `202`, inputs should stay locked; use browser Network tools to check that a retry sends the same `Idempotency-Key` without copying the bearer token into evidence. Attempt gateway deletion while a method is linked: expect `409`. Remove one method and its gateway to check normal cleanup; **leave the other method linked** for user deletion.
4. **Persistence checkpoint:** Before deleting the test records, record their UUIDs and restart the three data services:

   ```powershell
   docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml restart user-service travel-service payment-service
   docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml ps
   ```

   Wait for the services to become healthy, refresh the dashboard, and confirm the same user, method, travel UUIDs, itinerary, and participant remain. Do not run a destructive `down -v` on data you need to inspect.
5. **User deletion:** With the remaining method and traveler/travel participation recorded, delete the disposable user. `202` means cleanup is incomplete: inspect method status/failure and retry the same user deletion after resolving the dependency. Only record PASS at `204` after auth access is revoked, owned traveler/method data is gone, and the travel/destinations remain with the deleted traveler removed. Then delete the leftover gateway, travel, and disposable destinations. See [deletion recovery](../operations.md#resuming-user-deletion).

Check a deliberately invalid UUID and a missing ID through the API or UI; expect `400`/`404` without a crash.

The detailed [browser walkthrough](browser-checks.md) can be used as a page-by-page tick sheet. Full Chrome/Firefox proof requires actual results in both browsers; a Vite build or unit test is insufficient.

## 4. Request IDs, logs, and database state

Use `$traceId` from section 2 and inspect the participating services:

```powershell
docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml logs --no-color --since=10m api-gateway auth-service user-service | Select-String $traceId
```

**Expected:** gateway, auth validation, and user-service request lines share the `traceId`; each line has `timestamp`, `service`, `instanceId`, `severity`, `operation`, and `status`. The `instanceId` stays constant per process. Confirm no password, bearer token, provider credential/reference, or raw card data appears. Check an invalid token and a controlled backend error with separate IDs. The collector currently has no application export or searchable retained backend: mark OTLP ingestion/search **BLOCKED**, not PASS, until that is deployed and tested. See [tracing limits](tracing.md).

On staging, inspect Flyway history in each PostgreSQL database and a representative Neo4j travel aggregate using the database-owner access path. Compare stored IDs with the API records and check that a service restart does not erase them. Inspect schema constraints and indexes without exporting credential columns. If a user create fails between auth and users databases, follow the [reconciliation procedure](../operations.md#reconciling-failed-user-creation); this remains an implementation gap, not a passing test.

## 5. Jenkins, SonarQube, and Gitea PR gate

Work through 5A–5E in order. The checked-in [CI Compose file](../../infra/compose/compose.ci.yml) starts **only** Jenkins and SonarQube controllers on `127.0.0.1:8090` and `127.0.0.1:9000`. It does not create a Jenkins agent, install plugins, or make either service reachable from Gitea. Use a reachable HTTPS Jenkins URL for webhooks; `localhost` on the Gitea server means the Gitea server itself. Record the actual URLs and software versions, but no tokens.

### 5A. Bring up controllers and finish setup

1. From the repository root on the Docker host, start and inspect the controllers:

   ```powershell
   docker compose -f infra/compose/compose.ci.yml up -d
   docker compose -f infra/compose/compose.ci.yml ps
   ```

2. Open `http://127.0.0.1:8090` for Jenkins and `http://127.0.0.1:9000` for SonarQube **on that host**. Complete their first-run setup and replace initial administrator credentials. If Jenkins asks for its unlock password, retrieve it privately with `docker compose -f infra/compose/compose.ci.yml exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword`; never save its output as evidence.
3. Provision a Linux Jenkins agent labelled **exactly** `travel-plan-ci`. It needs JDK 21, Node.js 22/npm, Git, SonarScanner CLI, Docker CLI/daemon access, Compose v2, Python, Ansible, `ansible-lint`, and the `community.docker` collection. In **Manage Jenkins → Nodes**, confirm the agent is online. On the agent, have its administrator check `java -version`, `node --version`, `npm --version`, `sonar-scanner --version`, `docker version`, `docker compose version`, `ansible --version`, `ansible-lint --version`, and `ansible-galaxy collection list community.docker`. When the PR job is created in 5B, its **Toolchain** stage must pass on this agent; if it stays queued, check the label and connectivity. The [deployment setup](../deployment.md#jenkins-configuration) lists the full requirements.
4. In **Manage Jenkins → Plugins**, install/verify Pipeline, Multibranch Pipeline, Gitea, SonarQube Scanner for Jenkins, JUnit, and Credentials support. Configure a Gitea access token in Jenkins credentials with only the repository/API permissions needed to discover PRs and publish status. In **Manage Jenkins → System → Gitea Servers**, add the Gitea URL reachable from Jenkins and select that credential. The [Jenkins Gitea plugin instructions](https://plugins.jenkins.io/gitea/) describe the server and token fields.
5. Expose Jenkins through an approved HTTPS address reachable **from Gitea** and **from SonarQube**. Record the address and verify network access from those servers. Loopback-only CI Compose is suitable for local UI setup, not remote webhook delivery.

**Stop point:** If the agent, HTTPS endpoint, or Gitea credential is missing, mark 5A **BLOCKED**. Do not treat the two running controller containers as a working CI system.

### 5B. Wire the PR job and the required Gitea check

1. In Jenkins, create a **Multibranch Pipeline** or Gitea **Organization Folder** for this repository. Select the Gitea server/credential, discover same-repository pull requests, and use `Jenkinsfile` at the repository root. Restrict this validation job to PRs; keep the real `main` release in a separate job. Keep fork PRs untrusted unless the Jenkins administrator has reviewed their trust policy. The PR job must run on a Linux agent and must not have release/deploy credentials.
2. Choose one webhook mode: allow the Jenkins Gitea plugin to manage hooks with the approved credential, **or** have a Gitea repository administrator add the webhook shown by the Jenkins job. In Gitea **Repository → Settings → Webhooks**, inspect **Recent Deliveries** and use **Test Delivery**. Confirm a successful delivery reaches Jenkins, then confirm a real PR or push event starts the expected job. A synthetic test delivery alone does not prove PR discovery. The branch has previously pushed successfully while a remote hook printed `fatal: ambiguous argument`; investigate that server-side warning if events or status updates fail.
3. Start with a clean working tree. In PowerShell, create a **disposable PR branch** from the current reviewed feature branch, which contains the updated `Jenkinsfile`, and push an empty first commit:

   ```powershell
   git status --short
   git switch build-security-data-fixes
   git pull --ff-only origin build-security-data-fixes
   $ciBranch = 'ci-gate-rehearsal-' + (Get-Date -Format 'yyyyMMdd-HHmm')
   git switch -c $ciBranch
   git commit --allow-empty -m "Start PR gate rehearsal"
   git push -u origin HEAD
   ```

   In Gitea, open a PR from `$ciBranch` into the branch whose rule you will inspect, normally `main`; keep it open and **unmerged**. The PR may contain the unmerged feature work, so it is strictly a gate rehearsal. Let the first clean run finish so Gitea shows the **exact** Jenkins status context name on the PR head commit. Open the Jenkins run and check that **Backend verification**, **API contracts**, **Dashboard tests, lint and build**, **Dependency advisories**, and **Deployment playbook checks** ran. Check **Test Result** for JUnit and **Artifacts** for JAR/UI output. Sonar, image publish, and deploy should be skipped on a PR.
   If this baseline job fails for a real dependency, environment, or code issue, resolve and rerun it before adding the deliberate failure in 5C.
4. In Gitea **Repository → Settings → Branches**, edit the target branch rule. Disable direct pushes so changes go through PRs, leave PR merges available, enable **Status checks**, and select/enter the **exact context** observed in step 3. Save the rule. Enable the setting requiring administrators to follow branch protection if your audit must show nobody can force-merge around the failed check. Do not guess the context string; a typo can leave the gate ineffective. See [Gitea protected branches](https://docs.gitea.com/usage/access-control/protected-branches/).

**Evidence:** Gitea webhook delivery ID/result, Jenkins PR job URL/build number, checked-out SHA, exact status context, and a screenshot of the saved branch rule with secrets hidden. If no PR build or no status appears, mark **FAIL** once the configuration is in place; otherwise mark **BLOCKED** and record what is missing.

### 5C. Prove that a failing PR cannot merge, then recover

1. On the disposable PR branch only, add this temporary Vitest file, commit it, and push it. If you are using PowerShell, run from the repository root:

   ```powershell
   @'
   import { expect, test } from 'vitest'
   test('manual CI gate rehearsal', () => { expect(1).toBe(2) })
   '@ | Set-Content admin-ui/src/ci-gate-rehearsal.test.ts
   git add admin-ui/src/ci-gate-rehearsal.test.ts
   git commit -m "Rehearse failed CI gate"
   git push
   ```

2. Wait for the **new** Jenkins run. In its console, confirm `npm run test:ci` fails on `ci-gate-rehearsal.test.ts`. Later stages may be skipped after that failure; do not expect every stage to run in this deliberately failing run. In Gitea, confirm the **new head SHA** shows the same required Jenkins context as failed and the merge action is disabled. Do **not** click a force-merge option.
3. Remove only that temporary file on the **same branch** and push another commit:

   ```powershell
   Remove-Item -LiteralPath admin-ui/src/ci-gate-rehearsal.test.ts
   git add -u admin-ui/src/ci-gate-rehearsal.test.ts
   git commit -m "Restore passing CI gate"
   git push
   ```

4. Confirm the next Jenkins run is green at its **new** SHA, JUnit and artifacts are present, and Gitea shows the required context as successful on that SHA. Leave the PR unmerged, then close the disposable PR and delete its branch through the approved repository process when evidence has been saved.

**PASS requires both outcomes:** failed status blocks merge, then a corrected commit passes the same required context. A green Jenkins page without a Gitea status, or a failed job that still allows merge, is **FAIL**.

### 5D. Check SonarQube separately

SonarQube Community Build does not provide native PR analysis/decoration. This repository's `Jenkinsfile` therefore runs **SonarQube analysis** and **Quality gate** on every PR build against the shared `travel-plan` project, so a failed gate fails the PR check; image publication and deployment run only for `main`. Never use that job merely to test a quality gate: it can publish six images and run Ansible.

1. In SonarQube, create a **separate rehearsal project** with a distinct key. In Jenkins **Manage Jenkins → System**, configure the SonarQube installation name `travel-plan-sonarqube` and its protected analysis token. Configure a SonarQube webhook to the externally reachable **`<Jenkins HTTPS URL>/sonarqube-webhook/`** (keep the final slash). Confirm SonarQube shows a successful delivery. See [Sonar's Jenkins webhook procedure](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/ci-integration/jenkins-integration/pipeline-pause).
2. Create a separate Jenkins **Pipeline** job using **Pipeline script** (not the repository's `Jenkinsfile`). Have a Jenkins administrator review the script below, replace `YOUR_GITEA_READ_CREDENTIAL` with a read-only checkout credential ID, and confirm the source branch is the reviewed branch. This job has **no** image publish or deploy stage. Do not set PR or branch analysis parameters for Community Build. It exercises the webhook/gate against the separate rehearsal project; it is not evidence of real `main` analysis.

   ```groovy
   pipeline {
     agent { label 'travel-plan-ci' }
     stages {
       stage('Checkout') {
         steps {
           git branch: 'build-security-data-fixes',
               credentialsId: 'YOUR_GITEA_READ_CREDENTIAL',
               url: 'https://platform.zone01.gr/git/pkerasid/travel-plan.git'
         }
       }
       stage('Build') { steps { sh './mvnw -B -ntp verify' } }
       stage('Sonar rehearsal') {
         steps {
           withSonarQubeEnv('travel-plan-sonarqube') {
             sh 'sonar-scanner -Dsonar.projectKey=travel-plan-ci-rehearsal'
           }
         }
       }
       stage('Quality gate') {
         steps {
           timeout(time: 10, unit: 'MINUTES') {
             waitForQualityGate abortPipeline: true
           }
         }
       }
     }
   }
   ```

   If you chose a different rehearsal project key in step 1, replace `travel-plan-ci-rehearsal` in the script too. Select **Build Now** and follow **Console Output**.
3. Run the scratch job once and read the rehearsal project's measured values in SonarQube. With **Administer Quality Gates** permission, open **Quality Gates → Create**, make a custom gate, add a condition using a metric that has an observed value, and set its threshold so that value fails. Assign the custom gate to **only** `travel-plan-ci-rehearsal`; do not change the instance default or the real `travel-plan` project. See [Sonar's custom-gate instructions](https://docs.sonarsource.com/sonarqube-community-build/quality-standards-administration/managing-quality-gates/managing-custom-quality-gates). Run the scratch job again: SonarQube should show a failed gate, its webhook delivery should succeed, and Jenkins should stop at **Quality gate**. Restore a passing condition and run once more: Jenkins should continue and finish green. Record the changed condition and remove the temporary project/gate association after the rehearsal.
4. Create the real **separate main Pipeline job** with **New Item → Pipeline → Pipeline script from SCM**. Point Git at this repository's `main` branch and set the script path to `Jenkinsfile`. Restrict its trigger to reviewed `main` updates. Configure `REGISTRY_HOST`, `IMAGE_PREFIX`, and `DEPLOY_INVENTORY` as job environment values; add Jenkins username/password credential ID `travel-plan-registry` and bind the protected application secrets listed in 6A. After the reviewed implementation reaches `main`, prepare the target with `infra/ansible/prepare.yml` and have the inventory, TLS files, registry access, and protected secrets in section 6 ready. **Only then** run the main job. It publishes the six SHA-tagged images **and immediately calls** `deploy.yml`; do not run it with an unprepared target. Confirm its analysis appears under project key `travel-plan`, the quality gate passes, and the build uses the reviewed SHA. If the Sonar/release stages are unexpectedly skipped, stop and inspect the job's branch identification (`BRANCH_NAME`/`GIT_BRANCH`) before claiming a pass. If prerequisites are absent, leave this real-job check **BLOCKED**.

### 5E. Review dependency and image findings

1. Open **Dependency advisories** in the Jenkins run and save both outputs: root `npm audit --audit-level=high` and `admin-ui` `npm audit --audit-level=high`. A green stage means those two npm checks passed at the configured threshold only.
2. Obtain a Java dependency report and scans of **all six SHA-tagged images** from your approved scanners. These checks are **not** stages in the current `Jenkinsfile`; mark them **BLOCKED** until a scanner and reports exist. Match each report to the exact release SHA, image digest, and scanner database/update date.
3. For each high or critical finding, record package/image, version, severity, affected SHA, proposed upgrade or mitigation, reviewer, and decision. A known exception needs an explicit recorded decision; absence of a report is not a clean result.

Overall section 5 is **PASS** only with observed PR rejection/recovery, a working status gate, the relevant Sonar result, and completed advisory reviews. Otherwise record the individual PASS/FAIL/BLOCKED results; the presence of stages in `Jenkinsfile` alone is not evidence.

## 6. Clean, repeat, and rollback staging deployment

Use a **disposable Debian/Ubuntu staging host** and run these shell commands on the configured **Linux Ansible controller/agent**, from a checkout of the exact release commit. The local Windows PowerShell commands in sections 1–4 are a different environment. Do not point this procedure at production or at the checked-in `infra/ansible/inventory.ini`, which uses `127.0.0.1` and `ansible_connection=local`.

### 6A. Prepare the target and release inputs

1. Ask the deployment owner for a reviewed staging inventory containing a `[travel_plan]` host, SSH/become access, a hostname on the TLS certificate, and network access to the image registry. Store the inventory outside the repository if it contains private addresses or credentials. A minimal shape is shown below; replace every example name and address. `deployment_health_hostname` must resolve **on the target host** and match its serving certificate.

   ```ini
   [travel_plan]
   staging-1 ansible_host=staging.internal.example ansible_user=deploy

   [travel_plan:vars]
   deployment_health_hostname=staging.example.org
   deployment_ca_path=/opt/travel-plan/tls/certs/dev-ca.crt
   ```

   Confirm the controller can reach the reviewed host with `ansible -i "$DEPLOY_INVENTORY" travel_plan -m ping` after setting `DEPLOY_INVENTORY` in 6B. This checks Ansible connectivity, not application health.
2. Confirm a **full 40-character reviewed commit SHA**. For the clean `site.yml` rehearsal in 6C, all six images must already be published under `IMAGE_PREFIX` with that SHA tag: `auth-service`, `user-service`, `travel-service`, `payment-service`, `api-gateway`, and `admin-ui`. The registry must refuse replacement of an existing SHA tag. Record the six image digests. If these images do not exist yet, the reviewed `main` Jenkins job can publish them **after** a first target is prepared with `prepare.yml`; that job also deploys to that target. Use a **second fresh disposable staging host** for the clean `site.yml` rehearsal, or a separately approved image-publication process. The current feature branch has not been merged, so these release checks remain pending until an explicitly reviewed SHA is available.
3. On the target, provision the files required by [deploy.yml](../../infra/ansible/deploy.yml) under `/opt/travel-plan/tls/certs`: `dev-ca.crt`, `gateway.crt`, `gateway.key`, `server.crt`, `server.key`, `vault.crt`, `vault.key`, `public.crt`, `private.key`, and `trusted/dev-ca.crt`. Replace the example CA/certs with the approved staging CA and matching certificates as needed; keep key files private. Set Ansible `deployment_health_hostname` to the hostname on the serving certificate and `deployment_ca_path` to the matching **target-side** CA path. The client-side `CA_FILE` below must trust the same serving chain. The playbook checks file presence but does not issue certificates or initialize Vault.
4. Supply `REGISTRY_USER`/`REGISTRY_PASSWORD` and the required application values as **protected Jenkins environment bindings or Ansible Vault variables**: `POSTGRES_SUPERUSER_PASSWORD`, `AUTH_DB_PASSWORD`, `USERS_DB_PASSWORD`, `PAYMENTS_DB_PASSWORD`, `NEO4J_PASSWORD`, `AUTH_TOKEN_SECRET` (32+ characters), `ADMIN_USERNAME`, `ADMIN_PASSWORD`, and `AUTH_INTERNAL_SERVICE_TOKEN`. Set optional provider sandbox values only if available. The playbook rejects empty/placeholder required values. Never put secret literals in `-e`, shell history, screenshots, inventory committed to Git, or evidence.
5. If staging already holds data, confirm current PostgreSQL and Neo4j backups exist and that the restore procedure has been rehearsed. For a new empty staging host, record that no existing data needs protection, then take backups **after the first deployment and before rollback**. Record backup IDs and a restore owner. A Compose volume is not a backup. If the host is fresh, `site.yml` will install Docker Engine/Compose and create `/opt/travel-plan`; if conflicting distribution Docker packages are installed, preparation stops for manual migration.

**Stop point:** Missing host, registry images/access, TLS files, protected secrets, or backups for existing data makes 6A **BLOCKED**. Fix those inputs before running a deployment.

### 6B. Check the playbooks before deployment

Set these **non-secret** variables on the Linux controller from approved values; `DEPLOY_HOST` is the DNS name used by the outside HTTPS probe. Confirm `RELEASE_TAG` has 40 lowercase hex characters. Do not paste values from this example as real targets.

```sh
export DEPLOY_INVENTORY='/secure/path/reviewed-staging.ini'
export RELEASE_TAG="$(git rev-parse HEAD)"
export REGISTRY_HOST='registry.example.org'
export IMAGE_PREFIX='registry.example.org/team/travel-plan'
export DEPLOY_HOST='staging.example.org'
export CA_FILE='/secure/path/staging-ca.crt'
printf '%s' "$RELEASE_TAG" | grep -Eq '^[0-9a-f]{40}$'
ansible -i "$DEPLOY_INVENTORY" travel_plan -m ping
ansible-playbook --syntax-check -i "$DEPLOY_INVENTORY" infra/ansible/site.yml
ansible-lint infra/ansible/site.yml infra/ansible/prepare.yml infra/ansible/deploy.yml
```

Replace the example host, prefix, inventory, and CA paths with approved values before running. Compare `RELEASE_TAG` against the reviewed SHA, then check that the SHA and Ansible commands exit `0`; the ping must name only the intended staging host. A syntax/lint pass does not prove a deployment.

If the six images need their **first** publication, use these checked inputs to prepare the first target with `ansible-playbook -i "$DEPLOY_INVENTORY" infra/ansible/prepare.yml`. Then, **after review/merge to `main`**, run the real main Jenkins job to publish and deploy. For the separate clean `site.yml` rehearsal below, select a **second fresh disposable host** with its own reviewed inventory and TLS files, set `DEPLOY_INVENTORY`/`DEPLOY_HOST` to that host, and keep the same published `RELEASE_TAG`. If a reviewed image-publication process already supplied the six tags, you can use the original fresh host directly.

### 6C. Run the first clean deployment

From that checkout, with the protected values already bound in the controller environment/Vault, run:

```sh
ansible-playbook -i "$DEPLOY_INVENTORY" infra/ansible/site.yml \
  -e "release_tag=$RELEASE_TAG" -e "image_prefix=$IMAGE_PREFIX" -e "registry_host=$REGISTRY_HOST"
```

1. In the recap, confirm **zero failed and zero unreachable** hosts. `prepare.yml` should install/start Docker and Compose; `deploy.yml` should copy configuration, render `/opt/travel-plan/.env` mode `0600`, pull the six exact-tag images, wait for container health, and run its built-in HTTPS `200`/API `401` checks. Do not print `.env` or `docker compose config` into evidence because they can expose secrets.
2. From a client that resolves `DEPLOY_HOST`, run the independent TLS probes below. They must verify the certificate with the approved CA; do **not** add `-k`/`--insecure`:

   ```sh
   curl --cacert "$CA_FILE" -sS -o /dev/null -w '%{http_code}\n' "https://$DEPLOY_HOST:8443/health"
   curl --cacert "$CA_FILE" -sS -o /dev/null -w '%{http_code}\n' "https://$DEPLOY_HOST:8443/api/users"
   ```

   **Expected:** `200`, then `401`. Also sign in with the staging test admin and perform one authenticated read; record only its status, request ID, and redacted response summary.
3. On staging, inspect Compose `ps` and the six running application image names/digests. Each must match `IMAGE_PREFIX/<service>:<RELEASE_TAG>`. Note the container IDs. Create **one disposable record on staging** through the admin UI/API and record its UUID for repeat/rollback checks. Records created in the **local** simulator in section 3 are not present on staging.

### 6D. Repeat the same release

Run `infra/ansible/deploy.yml` with the **same** inventory, SHA, image prefix, registry host, and protected values:

```sh
ansible-playbook -i "$DEPLOY_INVENTORY" infra/ansible/deploy.yml \
  -e "release_tag=$RELEASE_TAG" -e "image_prefix=$IMAGE_PREFIX" -e "registry_host=$REGISTRY_HOST"
```

Compare its recap, changed-task count, image digests, and container IDs with 6C. A repeat may report safe reconciliation changes; investigate unexplained replacement or data loss. Repeat the TLS `200`/`401` probes and the authenticated read of the **same staging UUID**. PASS requires healthy services and unchanged data, not literally zero changed tasks.

### 6E. Rehearse rollback on staging

1. Choose a **previously published full SHA** (`PREVIOUS_TAG`) whose six image digests you recorded. Review its Flyway/Neo4j compatibility with the **current** data. Database migrations are not automatically reversed by `deploy.yml`. If compatibility is unknown or there is no earlier release, mark rollback **BLOCKED**; do not try an arbitrary old tag.
2. Take and verify fresh staging PostgreSQL and Neo4j backups using the team's approved backup/restore procedure. Record backup IDs and the person responsible for restoration. Keep the staging test UUID from 6C.
3. Set `PREVIOUS_TAG` to the reviewed old SHA and deploy it (the registry and secret inputs remain protected):

   ```sh
   export PREVIOUS_TAG='REPLACE_WITH_PREVIOUS_40_CHARACTER_SHA'
   printf '%s' "$PREVIOUS_TAG" | grep -Eq '^[0-9a-f]{40}$'
   ansible-playbook -i "$DEPLOY_INVENTORY" infra/ansible/deploy.yml \
     -e "release_tag=$PREVIOUS_TAG" -e "image_prefix=$IMAGE_PREFIX" -e "registry_host=$REGISTRY_HOST"
   ```

4. Confirm zero failed/unreachable hosts, six image tags/digests match `PREVIOUS_TAG`, HTTPS returns `200`, anonymous API returns `401`, and an authenticated read of the staging test UUID still works. Record any migration or data incompatibility as **FAIL** and follow the approved restore plan. Afterwards, redeploy the reviewed current `RELEASE_TAG` and repeat the probes so staging ends at the intended version.

Record the Ansible job/build IDs, target hostname, SHA/digests, recap, health results, staging UUID, backup IDs, and rollback outcome. Redact credentials and private network details before sharing. A successful Compose update does not prove zero downtime or database high availability. Mark each of 6C, 6D, and 6E **BLOCKED** if its required infrastructure has not been provisioned; do not mark section 6 PASS from source files or syntax checks alone.

## 7. Real Stripe and PayPal sandbox verification

Only run this section after you have provider-issued sandbox credentials and a trusted mapping between each local user and the corresponding Stripe customer / PayPal vault customer. The fake simulator cannot supply that proof.

1. In the provider sandbox, complete Stripe SetupIntent attachment to the intended sandbox customer; for PayPal, complete the approved setup-token exchange to a vault payment token. Store credentials in the protected deployment secret source. Never enter PAN/CVV or provider secrets in this app or evidence.
2. Through the admin API/UI, connect each resulting opaque reference to the **mapped** local user. Confirm the provider dashboard and local `ACTIVE` record refer to the same customer and safe display metadata. Record redacted provider object IDs and method UUIDs.
3. Verify label/default edits, duplicate reference rejection, provider rejection/unavailability state, same-key retry, and deletion/detachment in the provider dashboard. Confirm a failed cleanup leaves a visible failed/pending record and does not claim success.

Without both providers' credentials and ownership mapping, mark this section BLOCKED. Do not mark it PASS based on `pm_simulatedvisa001` or `VAULT_SIM_001`.

## 8. Replica and failure checks

The current base Compose file publishes gateway port `8080` on the host. Scaling `api-gateway` to two replicas with that unchanged port causes a collision; the `compose.scale.yml` example alone does not demonstrate full Phase 11. Start with a **single business service** on the disposable local stack:

```powershell
docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml up -d --scale user-service=2 user-service
docker compose --env-file .env.payment-sim -p travel-plan-payment-sim -f infra/compose/compose.yml -f infra/compose/compose.payment-sim.yml ps user-service
```

Send repeated authenticated `GET /api/users` requests with unique `X-Request-ID` values. Inspect user-service logs for both `instanceId` values and record response codes/latency. Stop **one identified user-service container** with `docker stop <container-name>`, repeat the reads, then restore it with the Compose `up -d --scale user-service=2 user-service` command. Expected: healthy survivor serves requests and stored data remains. Also stop all instances of a disposable service, confirm affected operations fail clearly while unrelated areas work, then restore it. Do not use the current `scripts/load/replica-smoke.sh` as authenticated-load evidence: it sends no bearer token. Full gateway scaling, formal authenticated load targets, and database failover remain BLOCKED until their ingress/test setup is completed.

## 9. Close the run

Create a dated results file such as `docs/audit/manual-results-YYYY-MM-DD.md` and fill one row for every section. Link redacted screenshots, Jenkins/Gitea/Sonar URLs, Ansible recap, certificate probe output, request IDs, and database checks. Include failures and blocked items; do not check off a phase in `plan.md` solely because code exists.

| ID | Environment / SHA | Expected | Observed result | PASS / FAIL / BLOCKED | Evidence link or blocker |
| --- | --- | --- | --- | --- | --- |
| 1 Local stack | | Healthy services and dashboard | | | |
| 2 Auth/gateway | | 401/403/200 and revocation as applicable | | | |
| 3 Browser CRUD | | All workflows in Chrome and Firefox at three widths | | | |
| 4 Logs/data | | Correlated IDs, persisted records, no sensitive log values | | | |
| 5A CI setup | | Controllers, agent, plugins, reachable webhook URL | | | |
| 5B PR wiring | | PR run, webhook delivery, required Gitea context | | | |
| 5C PR gate | | Failing commit blocked; corrected commit passed | | | |
| 5D Sonar | | Rehearsal failure/recovery; real main result when ready | | | |
| 5E Advisories | | npm, Java, and six image reports reviewed | | | |
| 6A–B Release inputs | | Reviewed host/SHA/images/TLS/secrets; playbooks check | | | |
| 6C Clean release | | Healthy SHA images and staging record | | | |
| 6D Repeat release | | Same SHA and staging record preserved | | | |
| 6E Rollback | | Compatible prior SHA, backups, probes, data preserved | | | |
| 7 Providers | | Both real sandbox references and customer ownership verified | | | |
| 8 Replicas/failure | | Survivor traffic, recovery, and recorded limits | | | |

Before final audit sign-off, review [the phase checklist](../../plan.md), [security rehearsal](security-rehearsal.md), [browser checklist](browser-checks.md), and [operations recovery](../operations.md). Record unresolved product gaps as open work, especially durable cross-database user creation, provider ownership, collector export/viewing, mutation audit records, and full gateway scaling.
