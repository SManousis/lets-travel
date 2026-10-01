#!/usr/bin/env bash
set -Eeuo pipefail

psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
  --set=auth_password="$AUTH_DB_PASSWORD" \
  --set=users_password="$USERS_DB_PASSWORD" \
  --set=payments_password="$PAYMENTS_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', 'auth_app', :'auth_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auth_app')\gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', 'users_app', :'users_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'users_app')\gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', 'payments_app', :'payments_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'payments_app')\gexec
SELECT 'CREATE DATABASE travel_auth OWNER auth_app' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'travel_auth')\gexec
SELECT 'CREATE DATABASE travel_users OWNER users_app' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'travel_users')\gexec
SELECT 'CREATE DATABASE travel_payments OWNER payments_app' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'travel_payments')\gexec
REVOKE ALL ON DATABASE travel_auth, travel_users, travel_payments FROM PUBLIC;
SQL
