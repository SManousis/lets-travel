#!/usr/bin/env bash
set -euo pipefail
output_dir="${1:-infra/tls/certs}"
mkdir -p "$output_dir"
openssl req -x509 -newkey rsa:4096 -sha256 -days 825 -nodes \
  -keyout "$output_dir/dev-ca.key" -out "$output_dir/dev-ca.crt" \
  -subj "/CN=Travel Plan Development CA"
openssl req -newkey rsa:2048 -nodes -keyout "$output_dir/gateway.key" \
  -out "$output_dir/gateway.csr" -subj "/CN=localhost"
openssl x509 -req -sha256 -days 398 -in "$output_dir/gateway.csr" \
  -CA "$output_dir/dev-ca.crt" -CAkey "$output_dir/dev-ca.key" -CAcreateserial \
  -out "$output_dir/gateway.crt" -extfile <(printf 'subjectAltName=DNS:localhost,IP:127.0.0.1')
rm -f "$output_dir/gateway.csr" "$output_dir/dev-ca.srl"
cp "$output_dir/gateway.key" "$output_dir/server.key"
cp "$output_dir/gateway.crt" "$output_dir/server.crt"
cp "$output_dir/gateway.key" "$output_dir/vault.key"
cp "$output_dir/gateway.crt" "$output_dir/vault.crt"
cp "$output_dir/gateway.crt" "$output_dir/public.crt"
cp "$output_dir/gateway.key" "$output_dir/private.key"
mkdir -p "$output_dir/trusted"
cp "$output_dir/dev-ca.crt" "$output_dir/trusted/dev-ca.crt"
mkdir -p "$output_dir/postgres" "$output_dir/vault" "$output_dir/neo4j"
cp "$output_dir/server.crt" "$output_dir/server.key" "$output_dir/postgres/"
cp "$output_dir/vault.crt" "$output_dir/vault.key" "$output_dir/vault/"
cp "$output_dir/public.crt" "$output_dir/private.key" "$output_dir/neo4j/"
chmod 600 "$output_dir"/*.key "$output_dir"/*/*.key
echo "Generated development CA and gateway certificate in $output_dir"
