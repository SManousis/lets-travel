# CI/CD setup from a fresh clone

How to run Jenkins, SonarQube, the private registry, and the local deploy target on your own
machine. Everything runs in Docker. Run commands from the repository root; use **Git Bash**
unless a step says PowerShell.

| Service | URL |
|---|---|
| Jenkins | http://127.0.0.1:8090 |
| SonarQube | http://127.0.0.1:9000 |
| Deployed app (after a `main` build) | https://localhost:8443 (self-signed certificate) |

## 1. Prerequisites

- Docker Desktop (WSL 2 backend), Git, and about 16 GB RAM.
- Give Docker enough memory. Create `%USERPROFILE%\.wslconfig`:

  ```ini
  [wsl2]
  memory=11GB
  swap=8GB
  ```

  Then run `wsl --shutdown` in PowerShell and restart Docker Desktop.
- A Gitea account that is an **admin collaborator** on `pkerasid/lets-travel`.

## 2. Generate local secrets

```sh
bash scripts/ci/init-secrets.sh
```

This creates `.env.ci-agent` and `.ci-secrets/` (deploy SSH key, registry password, and
`release.env` with the application secrets). Both are ignored by Git; never commit them.

## 3. Start Jenkins and SonarQube

```sh
docker compose -f infra/compose/compose.ci.yml up -d
```

## 4. SonarQube (http://127.0.0.1:9000)

1. Log in with `admin` / `admin` and set a new password.
2. **Projects → Create Project → Manually**: key `lets-travel` (lowercase, exactly), main branch `main`.
3. **My Account → Security → Generate Tokens**: type *Global Analysis Token*. Copy it.
4. **Administration → Configuration → Webhooks → Create**: URL `http://jenkins:8080/sonarqube-webhook/`
   (keep the final `/`). Without it, the Jenkins *Quality gate* stage hangs for 10 minutes.

## 5. Jenkins (http://127.0.0.1:8090)

1. Unlock with the output of
   `docker exec lets-travel-ci-jenkins-1 cat /var/jenkins_home/secrets/initialAdminPassword`,
   choose **Install suggested plugins**, create your admin user, keep Jenkins URL `http://127.0.0.1:8090/`.
2. **Manage Jenkins → Plugins → Available**: install **Gitea** and **SonarQube Scanner**, then restart.
3. **Manage Jenkins → Nodes → Built-In Node → Configure**: Number of executors `0`.
4. **Manage Jenkins → Credentials → System → Global → Add Credentials**:

   | ID | Kind | Value |
   |---|---|---|
   | `sonarqube-token` | Secret text | SonarQube token from step 4 |
   | `gitea-token` | Gitea Personal Access Token | Gitea **Settings → Applications**: permissions *repository: Read and Write*, *user: Read*. Do **not** tick "public only". |
   | `lets-travel-registry` | Username with password | user `jenkins`, password from `.ci-secrets/registry-password.txt` |
   | `lets-travel-deploy-ssh` | SSH Username with private key | user `deploy`, key = whole contents of `.ci-secrets/deploy_ed25519` |
   | `lets-travel-release-env` | Secret file | upload `.ci-secrets/release.env` (only the deploy stage reads it) |

5. **Manage Jenkins → System**:
   - **SonarQube servers**: tick *Environment variables*, add name `lets-travel-sonarqube`,
     URL `http://sonarqube:9000`, token `sonarqube-token`.
   - **Gitea Servers**: add `https://platform.zone01.gr/git`. A red *HTTP 403* under the URL is
     expected (Zone01 blocks anonymous API calls); save anyway.
   - **Global properties → Environment variables**:

     | Name | Value |
     |---|---|
     | `REGISTRY_HOST` | `registry:5000` |
     | `IMAGE_PREFIX` | `registry:5000/lets-travel` |
     | `DEPLOY_INVENTORY` | `infra/ansible/inventory.ci.ini` |

6. **Manage Jenkins → Nodes → New Node**: name `lets-travel-pr-1`, Permanent Agent, remote root
   `/home/jenkins/agent`, label `lets-travel-ci`, launch *by connecting it to the controller*.
   Open the node, copy the `-secret` value, and set `JENKINS_AGENT_SECRET=` in `.env.ci-agent`.

## 6. Start the full CI stack

```sh
docker compose --env-file .env.ci-agent -f infra/compose/compose.ci.yml \
  -f infra/compose/compose.ci-agent.yml -f infra/compose/compose.ci-deploy.yml up -d --build
```

Use this same command after every reboot. The node from step 5.6 should show as online.
Then give the deploy target its dev TLS certificates (once):

```sh
docker exec -i lets-travel-ci-deploy-host-1 bash -s /opt/lets-travel/tls/certs < infra/tls/generate-dev-ca.sh
```

## 7. Create the pipeline job

The Gitea plugin cannot list another user's private repositories, so its *Repository* dropdown
stays empty. Load the checked-in job definition instead:

```sh
MSYS_NO_PATHCONV=1 docker exec -u 0 lets-travel-ci-jenkins-1 mkdir -p /var/jenkins_home/jobs/lets-travel
MSYS_NO_PATHCONV=1 docker cp infra/jenkins/lets-travel-job.xml lets-travel-ci-jenkins-1:/var/jenkins_home/jobs/lets-travel/config.xml
MSYS_NO_PATHCONV=1 docker exec -u 0 lets-travel-ci-jenkins-1 chown -R jenkins:jenkins /var/jenkins_home/jobs/lets-travel
docker restart lets-travel-ci-jenkins-1
```

Jenkins scans Gitea every minute and builds each branch and pull request.

## 8. Protect `main` in Gitea (once per repository)

**Repository → Settings → Branches → Add new rule**. **Type** the values by hand; pasted text can
carry invisible characters and the rule silently matches nothing.

- Branch name pattern: `main`
- Disable push
- Enable status check, pattern `lets-travel/pipeline/pr-main`
- Block merge on rejected reviews; block admins from overriding

## What runs

| Build | Stages |
|---|---|
| Every branch / PR | Maven tests, API lint, UI tests/lint/build, npm audit, Ansible lint, SonarQube analysis + quality gate |
| `main` only | + build and push 6 images to `registry:5000`, Ansible deploy to `deploy-host` |

## Troubleshooting

- **Quality gate waits forever**: the SonarQube webhook (step 4.4) is missing.
- **Sonar says a similar key `Lets-Travel` exists**: rename the project key to `lets-travel`.
- **Gitea token sees no repositories**: it was created as "public only", or your account is not a collaborator.
- **Jobs stay queued**: the agent is offline or lacks the `lets-travel-ci` label; check `docker logs lets-travel-ci-pr-agent-1`.
- **`git push` prints `fatal: ambiguous argument`**: a Zone01 server hook warning; the push still succeeds.
