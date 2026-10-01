#!/usr/bin/env bash
set -euo pipefail
base_url="${BASE_URL:-http://127.0.0.1:8080}"
count="${REQUESTS:-40}"
target="${TARGET:-/api/users}"
for i in $(seq 1 "$count"); do curl -fsS -o /dev/null -w '%{http_code}\n' "$base_url$target" || true; done | sort | uniq -c
