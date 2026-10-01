#!/usr/bin/env bash
set -euo pipefail
base_url="${BASE_URL:-http://127.0.0.1:8080}"
status=$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/api/users")
test "$status" = 401
echo "Unauthenticated business request correctly returned 401"
