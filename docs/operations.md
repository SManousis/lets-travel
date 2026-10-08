# Operations

`infra/ansible/site.yml` prepares a Debian/Ubuntu host and deploys the Compose environment. Credentials are supplied through Ansible Vault or an external secret manager. For a disposable stack already running on the base Compose file, scale a business service without a published host port:

```sh
docker compose -f infra/compose/compose.yml up -d --scale user-service=2 user-service
```

The base Compose file publishes gateway port `8080`, so two gateway replicas need a separately reviewed ingress/port override; the scale example above only tests user-service. See the [manual testing guide](audit/manual-testing-guide.md#8-replica-and-failure-checks) for the observed checks. Application health checks and private management ports are used for readiness. Database replication and failover remain separate concerns because named volumes do not provide database high availability.

## Upgrading an existing installation to persisted auth identities

Auth migration V3 creates `auth_identities`. Older provisioned passwords existed only in auth-service memory, so their hashes cannot be migrated. An installation with existing rows in the users database requires an explicit credential reset before normal traffic resumes.

1. Back up the auth and users databases, and put the gateway in maintenance mode so user writes and logins do not race the upgrade.
2. Start auth-service with the `infra` profile and confirm Flyway reached V3. Keep the bootstrap administrator credential available through the configured secret source.
3. Export `id`, `email`, `role`, and `status` from the users database. For **every** row, generate a unique strong replacement password through the approved secret-management process. Call the private `POST /api/auth/internal/provision` endpoint with `{ "userId": "<id>", "username": "<email>", "password": "<replacement>", "role": "<role>" }` and the `X-Internal-Service-Token` header. Do not place passwords in source control, shell history, or application logs.
4. For each user whose status is `DISABLED`, call the private `POST /api/auth/internal/identities/<id>` endpoint with `{ "username": "<email>", "role": "<role>", "enabled": false }` and the same internal credential. Confirm every users-database UUID has one matching auth identity with the expected role and enabled state. Resolve any `409` or `404` before opening traffic.
5. Deliver replacement passwords for active users through a secure channel. Restart auth-service and verify representative ADMIN and OPERATOR logins, disabled-user rejection, and direct-service token revocation. Then reopen the gateway.

This is a required one-time reset for upgrades from the memory-only auth implementation. There is currently no end-user password-change flow; subsequent password replacement remains an operator procedure until that feature is built.

## Reconciling failed user creation

User creation provisions an auth identity before inserting the user profile. If the profile insert fails, user-service now asks auth-service to disable that newly provisioned subject and revoke its sessions. The two databases still do not share a transaction. If auth-service is unavailable during compensation, an enabled identity can remain without a profile; even successful compensation leaves a disabled identity that reserves the username. A retry may therefore return a conflict until an operator reconciles the records.

1. Stop user creation and logins, then back up both databases. Record the failed request ID and the provisioned subject UUID from the service error log if available. Do not record passwords or bearer tokens.
2. Export `SELECT subject,username,role,enabled FROM auth_identities ORDER BY subject` from the auth database and `SELECT id,email,role,status FROM users ORDER BY id` from the users database. Compare the exports by `subject = id`, then check email/username, role, and enabled/status. The databases are separate, so perform the comparison outside SQL rather than assuming a cross-database join.
3. For each auth-only subject, call the private `POST /api/auth/internal/revoke/<subject>` endpoint with the internal service token. Confirm `enabled = false` and that an existing token for the subject is rejected. Investigate any failed revoke before reopening traffic.
4. For an auth-only subject confirmed to have no user row, back up its row and check for related sessions or external references. If the intended user must be created again, remove only that disabled orphan with `DELETE FROM auth_identities WHERE subject = :orphan_subject AND enabled = false`; record the UUID and deletion. Then retry creation through the API. Do not reuse a subject UUID or reactivate an orphan as a shortcut.
5. For a user-only row or mismatched fields, use the credential reset and identity synchronization procedure above with the existing user UUID. Re-export both tables and confirm every active user has exactly one matching enabled identity before reopening traffic.

This is an operator recovery procedure, not durable cross-database coordination. A persisted creation state and reconciliation worker are still needed to close the identity consistency gap.

## Upgrading legacy travel graph records

The earlier travel-service kept stops, activities, accommodation, transport, and participants only in process memory. Its Neo4j writes contained destination and top-level travel nodes. A restart lost the itinerary fields, so the new graph repository cannot infer them from the database.

1. Put travel writes in maintenance mode and back up Neo4j before deploying the new service. Record travel IDs and external references that may need reconciliation.
2. Inspect incomplete roots with `MATCH (t:Travel) WHERE NOT EXISTS { MATCH (t)-[:HAS_STOP]->(:DestinationStop) } RETURN t.id, t.name, t.startDate, t.endDate`. Restore each itinerary from an external source of record. While any incomplete root still has the `Travel` label, both its detail request and the whole travel-list request return `409 INCOMPLETE_LEGACY_TRAVEL`; do not reopen travel reads until every such root is reconciled or quarantined.
3. Before the new service initializes its normalized destination constraint, inspect duplicates with `MATCH (d:Destination) WITH toLower(trim(d.name)) AS name, toLower(trim(d.country)) AS country, collect(d.id) AS ids WHERE size(ids) > 1 RETURN name, country, ids`. Reconcile duplicate IDs and any external references before removing redundant nodes. Startup intentionally fails while duplicates violate the constraint.
4. For an incomplete root that can be replaced, record its old UUID, remove that specific root after backup with `MATCH (t:Travel {id: $id}) WHERE NOT EXISTS { MATCH (t)-[:HAS_STOP]->(:DestinationStop) } DETACH DELETE t`, then create the complete travel through the admin API. Record the old-to-new UUID mapping and reconcile any consumers of the old ID. If its itinerary cannot yet be reconstructed, preserve the root for operator review outside the live collection with `MATCH (t:Travel {id: $id}) WHERE NOT EXISTS { MATCH (t)-[:HAS_STOP]->(:DestinationStop) } REMOVE t:Travel SET t:LegacyTravelNeedsReview`. Track quarantined records with `MATCH (t:LegacyTravelNeedsReview) RETURN t.id, t.name, t.startDate, t.endDate`. A quarantined ID returns `404` from the travel API until it is restored as a complete travel.
5. Confirm the incomplete-root query in step 2 returns no rows before reopening reads. Verify complete aggregates with `MATCH (t:Travel)-[:HAS_STOP]->(s:DestinationStop)-[:AT]->(d:Destination) RETURN t.id, count(s) AS stops, collect(d.id) AS destinations ORDER BY t.id`. Check a representative itinerary and the travel list from a second travel-service instance before reopening writes.

Do not treat a top-level-only node as a completed itinerary. The original in-memory component data cannot be recovered from Neo4j alone.

## Upgrading legacy payment records

Payment migration V1 stored methods without gateway IDs. The earlier controller also kept gateways and methods only in memory, so records that existed only in a running process cannot be reconstructed after restart. Before applying V2, back up the payments database and check for duplicate `(provider, provider_reference)` pairs with `SELECT provider, provider_reference, count(*) FROM payment_methods GROUP BY provider, provider_reference HAVING count(*) > 1`; reconcile duplicates before the new unique index is created.

After V2, inspect `SELECT id, user_id, provider, provider_reference, status FROM payment_methods WHERE gateway_id IS NULL`. These rows remain in PostgreSQL, but payment-list/detail reads return `409 LEGACY_METHOD_REQUIRES_GATEWAY` while they are present. For each row, verify its owner, provider account, and reference outside this service, then create the appropriate gateway. If a trustworthy reference can be reconnected, record the old ID in the operator log, remove the backed-up legacy row, and create a new method through the admin API with a fresh UUID `Idempotency-Key`. Record the old-to-new ID mapping. Do not mark a legacy row `ACTIVE` by SQL without provider verification. Recheck that the `gateway_id IS NULL` query returns no rows before reopening payment reads.

Configure the Stripe and PayPal sandbox credentials through deployment secrets before connecting or deleting methods. If a provider call fails, inspect the method's safe `status` and `failureCode`, resolve credentials/provider availability, then retry the same create key or deletion request. Do not copy provider references or credentials into tickets or logs.

A failed deletion with `PROVIDER_REFERENCE_NOT_FOUND` needs an operator check of the original merchant account and provider dashboard. A `404` under current credentials cannot establish that the old account's token was detached. After verifying the provider resource is absent or detached, record that evidence and the method UUID, back up the row, and remove only that reconciled row with `DELETE FROM payment_methods WHERE id = :method_id AND status = 'FAILED' AND failure_stage = 'DELETE'`. A failed connection with `INVALID_PROVIDER_REFERENCE` was rejected before any provider request and can be removed through the method DELETE API. Other failed rows require the same provider/account reconciliation before local removal.

## Resuming user deletion

User migration V2 adds `users.deletion_stage`; payment migration V3 adds `payment_user_lifecycle`. Back up both databases before upgrading. The payment lifecycle row is a permanent tombstone once `deleting=true`: it prevents a new method from being connected for the deleted user. Travel cleanup records a `TravelerGuard` tombstone for each removed traveler ID; the travel service creates its uniqueness constraint at startup.

An administrator repeats `DELETE /api/users/<user-id>` after a `202` or a dependency failure. The service resumes from `STARTED`, `REVOKED`, `PAYMENTS_CLEAN`, or `TRAVEL_CLEAN`; `COMPLETE` returns `204`. It marks deletion progress before revoking auth, then disables the profile. It does not delete traveler rows until payment methods and travel participation have been cleaned. Inspect progress with `SELECT id,status,deletion_stage FROM users WHERE id = :user_id` and payment failures with `SELECT id,status,failure_code,failure_stage FROM payment_methods WHERE user_id = :user_id`. A `202` means methods remain; resolve provider errors and retry. A `503` means a dependency or storage call failed; restore it and retry. Never reset deletion progress or tombstones just to force a `204`.

The current implementation has not been exercised against deployed PostgreSQL, Neo4j, or credentialed provider sandboxes. Its synchronous `202` response is empty; the future operation resource and `Location` contract in the OpenAPI files remain to be implemented. Confirm cleanup and retry behavior in a deployed rehearsal before marking the user lifecycle or Phase 7 complete.

Run `scripts/load/replica-smoke.sh` with `compose.scale.yml` to record status/error counts during a 40-request authenticated read sample. Stop one replica and repeat; Docker DNS routes new connections to survivors. Stop all instances of one service to verify a controlled dependency failure, then restore it with Compose. Application services use bounded HTTP client timeouts and idempotency keys for retried writes; this local demonstration does not claim multi-host or database high availability.

## Resilience: timeouts, circuit breakers and retries

Every wait is bounded, so one slow or stopped dependency cannot tie up the services that call it.

| Where | Limit |
| --- | --- |
| Gateway to a service | connect 3 s, response 30 s, then `504` |
| Service to service (internal HTTP) | connect 2–3 s, response 3–10 s (30 s for account deletion clean-up) |
| travel-service to Neo4j | connect 5 s, wait for a pooled connection 10 s (`neo4j.connection-timeout`, `neo4j.acquisition-timeout`) |
| search-service to Elasticsearch | 20 s per request |

- **Circuit breakers** (`CircuitBreakers` in service-common, one per dependency host). After 5 calls in a row that
  could not reach a dependency, calls to it fail at once for 15 s, then one trial call decides whether to close the
  circuit. Only unreachable dependencies count; a dependency that answers (even `503`) answers quickly. Callers
  see the same `503 DEPENDENCY_UNAVAILABLE` as before, just without the wait.
- **Gateway retries.** A `GET` or `HEAD` whose connection fails is retried twice (50–200 ms apart). With several
  replicas the retry usually reaches a healthy one. Writes are never retried by the gateway; clients retry them
  with the same `Idempotency-Key`.
- **Startup.** travel-service waits for Neo4j (up to 12 attempts, backing off to 30 s) instead of crashing when the
  database is slow or still starting, which on a busy host could outlast the deployment's health wait.

## Fallbacks when a dependency fails

| Failure | What users see |
| --- | --- |
| Elasticsearch or search-service down | Search says it is limited and shows matching bookable travels from travel-service instead; changes are queued and indexed when it returns, and the 5-minute rebuild repairs anything missed |
| Neo4j down | Travel browsing, booking and recommendations answer `503` within about 10 s; accounts, payments, feedback, reports and dashboards keep working |
| Recommendations unavailable | The home page says so; search and browsing still work. Travelers without history get popular travels |
| Payment provider down or slow | The payment stays `PROCESSING` or `REQUIRES_RECONCILIATION` and is shown as pending with "Check payment status"; reconciliation settles it later and the place stays reserved meanwhile |
| payment-service down | Paying fails with `503`; the traveler dashboard shows no payment methods but loads |
| engagement-service down | Subscriptions, feedback, reports and dashboards answer `503`; the outbox keeps undelivered events |

## Request IDs

The browser sends an `X-Request-ID` with every API call, the gateway keeps it (or creates one), and every service
puts it in its log lines (`[requestId]` after the level) and forwards it on internal calls and provider calls.
Events carry the ID of the request that caused them, and travel-service logs their processing under it. To
follow a request, search all service logs for the ID shown in the browser's network panel or in an error body.
Logs never contain headers, tokens, passwords or card data.

## Parked events

engagement-service delivers its outbox to travel-service ([events](events.md#recommendation-history-delivery-phase-7)).
An event travel-service cannot apply (for example a missing field) is **parked** instead of blocking the events
behind it.

1. `GET /api/admin/events/status` shows how many events wait (`pending`), how many are parked and the oldest
   pending time. A growing `pending` count means travel-service or Neo4j is down; a non-zero `parked` count needs
   attention.
2. `GET /api/admin/events/parked` lists parked events with the reason travel-service gave. The request ID in
   travel-service's log line `Rejected event …` shows where the event came from.
3. Fix the cause (usually a consumer bug, then deploy the fix).
4. `POST /api/admin/events/parked/{eventId}/retry` puts the event back in the queue. Resending is safe: travel-service
   ignores events it has already applied and older versions of a subscription. The recommendation reconciliation
   (every 10 minutes) also repairs the history from engagement-service's records in the meantime.

## Scaling and its limits

`infra/compose/compose.scale.yml` runs two replicas of every application service. Services keep no state in
memory that requests depend on, so any replica can serve any request. Search and travel scale independently:

```sh
docker compose -f infra/compose/compose.yml -f infra/compose/compose.scale.yml up -d \
  --scale search-service=3 --scale travel-service=2
```

What replicas do **not** give:

- **Databases are single instances.** PostgreSQL, Neo4j Community and Elasticsearch each run one node. Application
  replicas share them, so they add request capacity, not database availability. Neo4j clustering needs the
  Enterprise edition; Elasticsearch would need at least three nodes for a fault-tolerant cluster; PostgreSQL would
  need streaming replication and failover. None of these are set up.
- **Elasticsearch and Neo4j are rebuildable.** The search index is rebuilt from travel-service, and the recommendation
  history from engagement-service, so losing either loses no business data. Neo4j also stores travels themselves,
  so it must be backed up like PostgreSQL.
- **Routing.** Compose health checks decide when a container starts serving, but Docker's DNS keeps a running
  replica in rotation even if it becomes unhealthy. The gateway's read retries cover a replica that is stopped or
  refuses connections; a replica that is up but answers errors stays routed until it is restarted. An orchestrator
  using the enabled readiness probes (`/actuator/health/readiness`) would remove it.
- **Scheduled jobs run on every replica.** The outbox relay, search rebuild and reconciliations are idempotent, so
  running twice is harmless but does the work twice.

## Load tests

`scripts/load/load.mjs` (Node 22, no dependencies) runs repeatable scenarios against a deployment. Each run
registers its own traveler accounts.

```sh
INSECURE_TLS=1 BASE_URL=https://localhost:8443 ADMIN_EMAIL=... ADMIN_PASSWORD=... \
  node scripts/load/load.mjs --scenario all --concurrency 20 --duration 60
```

Scenarios: `search`, `autocomplete`, `dashboards`, `subscriptions` (reserve and cancel on the published travel with
the most free places), `mixed` (a traveler journey) and `all`. Each operation reports requests, throughput, error
rate and p50/p95/p99/max latency, and passes when p99 is under 5 s (`--budget-ms`) with under 1% errors. Results and
the environment (commit, host, CPUs, memory) are saved in `load-results/`; copy the ones used as evidence into
[docs/audit/evidence.md](audit/evidence.md).

## Failure drills

`scripts/load/drill.mjs` stops parts of a **test** deployment and checks the documented behaviour, then starts them
again and checks recovery:

```sh
INSECURE_TLS=1 ADMIN_EMAIL=... ADMIN_PASSWORD=... DOCKER="docker exec lets-travel-ci-deploy-host-1 docker" \
  node scripts/load/drill.mjs --only replica,search,neo4j,payments,restart
```

| Drill | Expected |
| --- | --- |
| `replica` | One of two travel-service replicas stopped: all reads still succeed (needs `compose.scale.yml`) |
| `search` | Elasticsearch stopped: search `503`, browsing `200`; search returns after restart |
| `neo4j` | Neo4j stopped: travels and recommendations `503` quickly, accounts and dashboards `200`; travel-service recovers without a restart |
| `payments` | Payment simulator stopped: the rest of the app works, new payments stay pending; settled after restart |
| `restart` | PostgreSQL, Neo4j and Elasticsearch restarted: user, travel and searchable counts match, no events pending or parked |
