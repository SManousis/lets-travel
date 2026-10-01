#!/usr/bin/env bash
# Creates the ignored local CI secrets: .ci-secrets/ (deploy SSH key, registry password,
# release.env for the Jenkins deploy stage) and .env.ci-agent (compose values).
# Run once from the repository root in Git Bash.
set -euo pipefail

if [ -e .env.ci-agent ] || [ -e .ci-secrets ]; then
  echo ".env.ci-agent or .ci-secrets/ already exists; remove them to regenerate." >&2
  exit 1
fi

mkdir .ci-secrets
ssh-keygen -q -t ed25519 -N '' -C jenkins-deploy -f .ci-secrets/deploy_ed25519
registry_password=$(openssl rand -hex 16)
printf '%s' "$registry_password" > .ci-secrets/registry-password.txt
registry_hash=$(docker run --rm httpd:2.4-alpine htpasswd -Bbn jenkins "$registry_password" | head -1)

random() { openssl rand -hex "$1"; }
cat > .env.ci-agent <<EOF
# Paste the secret from Jenkins > Nodes > travel-plan-pr-1 after creating the node.
JENKINS_AGENT_SECRET=
DEPLOY_AUTHORIZED_KEY='$(cat .ci-secrets/deploy_ed25519.pub)'
REGISTRY_HTPASSWD='$registry_hash'
EOF

# Uploaded to Jenkins as the travel-plan-release-env Secret file; read only by the deploy stage.
cat > .ci-secrets/release.env <<EOF
POSTGRES_SUPERUSER_PASSWORD=$(random 16)
AUTH_DB_PASSWORD=$(random 16)
USERS_DB_PASSWORD=$(random 16)
PAYMENTS_DB_PASSWORD=$(random 16)
NEO4J_PASSWORD=$(random 16)
AUTH_TOKEN_SECRET=$(random 32)
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$(random 12)
AUTH_INTERNAL_SERVICE_TOKEN=$(random 24)
EOF

echo "Created .env.ci-agent and .ci-secrets/ (both ignored by Git)."
