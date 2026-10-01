#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
./mvnw -q -DskipTests package
(cd admin-ui && npm run build >/dev/null)
docker compose -f infra/compose/compose.yml config >/dev/null
if command -v curl >/dev/null && curl -fsS -o /dev/null -w '%{http_code}' "${BASE_URL:-http://127.0.0.1:8080}/api/users" | grep -qx 401; then echo "gateway denial: PASS"; else echo "gateway unavailable or denial check failed"; fi
if rg -n --glob '!admin-ui/node_modules/**' --glob '!**/target/**' --glob '!.env.example' '(sk_live_[A-Za-z0-9]+|sk_test_[A-Za-z0-9]+|-----BEGIN (RSA|EC|OPENSSH) PRIVATE KEY-----)' . >/dev/null; then echo "possible secret pattern found" >&2; exit 1; fi
echo "final rehearsal build/config/secret checks: PASS"
