# Local infrastructure

Copy `.env.example` to `.env`, replace every `change-me` value, build the jars,
then start the environment:

```sh
cp .env.example .env
./mvnw verify
docker compose --env-file .env -f infra/compose/compose.yml up --build -d
docker compose --env-file .env -f infra/compose/compose.yml ps
```

The application containers activate Spring's `infra` profile. That profile enables
Flyway migrations and bounded Hikari connection retries. Running services directly
without the profile keeps local startup tests independent of the databases.

The Compose file exposes only the UI (`127.0.0.1:5173`) and gateway
(`127.0.0.1:8080`) to the host. Application services and databases are reachable
only on the private `backend` network. Named volumes preserve PostgreSQL and Neo4j
data across container recreation; do not use `down -v` when testing persistence.

PostgreSQL initialization creates separate `travel_auth`, `travel_users`, and
`travel_payments` databases and least-privilege application users. Neo4j constraints
and indexes are applied with the documented repeat-safe Cypher script:

```sh
docker compose -f infra/compose/compose.yml exec neo4j \
  cypher-shell -u neo4j -p "$NEO4J_PASSWORD" -f /var/lib/neo4j/import/init/001-constraints.cypher
```

Stop without removing data with `docker compose ... stop`; remove containers while
keeping volumes with `docker compose ... down`; remove data only intentionally with
`docker compose ... down -v`. Database backup/restore procedures will be expanded
with deployment automation in Phase 10.
