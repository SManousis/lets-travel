# Durable Travel Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist and reload complete travel aggregates and destinations from Neo4j across service instances.

**Architecture:** `TravelStore` keeps validation and delegates graph-profile data access to `Neo4jTravelRepository`. That repository stores all owned itinerary and participation nodes in managed transactions and projects complete aggregates in one graph query. The default profile retains an in-memory repository path for startup and focused unit tests.

**Tech Stack:** Java 21, Spring Boot 4.1, Neo4j Java Driver 6.0.2, Neo4j Harness 2026.08.1, JUnit 5, Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-09-30-travel-graph-persistence-design.md`

## Global Constraints

- Work on `build-security-data-fixes` in the current checkout; do not create a worktree.
- Keep the existing travel HTTP shape and UUID/domain records in this package.
- Use Neo4j as the only source of truth when the `infra` profile provides a `Driver`.
- Use one graph transaction for each aggregate mutation and close each `Session`.
- Delete only travel-owned nodes; shared destinations must survive travel deletion.
- Do not silently return an incomplete legacy travel as a complete itinerary.

---

## File structure

| File | Responsibility |
|---|---|
| `travel-service/src/main/java/com/travelplan/travel/Neo4jTravelRepository.java` | Graph CRUD, atomic writes, complete read projection, schema constraints. |
| `travel-service/src/main/java/com/travelplan/travel/TravelStore.java` | Domain validation and repository selection. |
| `travel-service/src/test/java/com/travelplan/travel/Neo4jTravelRepositoryTest.java` | In-process graph lifecycle and cross-instance behavior. |
| `travel-service/pom.xml` | Test-scoped Neo4j Harness dependency. |
| `docs/operations.md` | Legacy graph upgrade procedure and verification queries. |
| `docs/audit/project-audit-2026-09-29.md` | Dated remediation evidence with remaining limits. |

### Task 1: Establish failing graph lifecycle evidence

**Files:** Modify `travel-service/pom.xml`; create `travel-service/src/test/java/com/travelplan/travel/Neo4jTravelRepositoryTest.java`.

**Produces:** a test harness that opens two `TravelStore` instances over the same in-process Neo4j database.

- [ ] **Step 1: Add test dependency.** Add `org.neo4j.test:neo4j-harness:2026.08.1` with `test` scope to `travel-service/pom.xml`.
- [ ] **Step 2: Write a failing cross-instance test.** Create two destinations and a travel with two stops, activity, accommodation, transport, and participant through the first store. Read from the second store and assert all IDs, fields, ordering, and participant ID are present. Update and delete, then query Neo4j to assert no owned orphan nodes remain and both destinations survive.
- [ ] **Step 3: Run red.** Run `mvnw.cmd -B -ntp -pl travel-service -Dtest=Neo4jTravelRepositoryTest test`; current memory-backed reads should fail on the second instance.

### Task 2: Persist destinations and graph schema

**Files:** Create `Neo4jTravelRepository.java`; modify `TravelStore.java`.

**Interfaces:** `Neo4jTravelRepository(Driver)`; `List<Destination> destinations()`, `Destination destination(UUID)`, `Destination createDestination(Destination)`, `Destination updateDestination(Destination)`, `void deleteDestination(UUID)`.

- [ ] **Step 1: Add repeat-safe schema initialization.** Execute `CREATE CONSTRAINT ... IF NOT EXISTS` for travel, destination, stop, activity, accommodation, transport, and participation UUIDs. Populate normalized destination names/countries on legacy nodes and constrain the pair.
- [ ] **Step 2: Implement destination reads/writes.** Read `createdAt` and preserve it on update. Check duplicate normalized name/country and referenced stops in write transactions. Do not change the local maps in graph mode.
- [ ] **Step 3: Verify destination behavior.** Add assertions for second-instance destination visibility, duplicate rejection, update visibility, and referenced-destination `409`; run the focused test.
- [ ] **Step 4: Commit.** Commit the schema and destination slice with `feat: persist travel destinations in neo4j`.

### Task 3: Persist and reload complete travel aggregates

**Files:** Modify `Neo4jTravelRepository.java`, `TravelStore.java`, and `Neo4jTravelRepositoryTest.java`.

**Interfaces:** `List<Travel> travels()`, `Travel travel(UUID)`, `Travel save(Travel)`, `Travel update(Travel)`, `void delete(UUID)`.

- [ ] **Step 1: Write aggregate mutations.** In one managed write transaction, create or update the travel root and all stop/activity/accommodation/transport nodes and relationships. On update, replace only itinerary nodes and retain participants. On delete, detach/delete the root and owned nodes while preserving destinations.
- [ ] **Step 2: Write complete graph reads.** Use one Cypher projection query for root, ordered itinerary lists, and participants; map strings to UUID, `LocalDate`, and `Instant`. Reject a legacy travel with no stops as `INCOMPLETE_LEGACY_TRAVEL`.
- [ ] **Step 3: Verify cross-instance and cleanup behavior.** Run the focused harness test; assert the second store sees a complete travel after create and update, and graph queries show no owned nodes after delete.
- [ ] **Step 4: Commit.** Commit the aggregate slice with `feat: persist complete travel itineraries`.

### Task 4: Persist participation and document upgrades

**Files:** Modify `Neo4jTravelRepository.java`, `TravelStore.java`, `Neo4jTravelRepositoryTest.java`, and `docs/operations.md`.

**Interfaces:** `Travel addParticipant(UUID travelId, UUID travelerId)` and `void removeParticipant(UUID travelId, UUID travelerId)`.

- [ ] **Step 1: Persist participation.** Use a deterministic participation ID derived from travel and traveler UUIDs; `MERGE` on add and make removal idempotent. Keep other travelers and the travel unchanged.
- [ ] **Step 2: Verify participation across instances.** Add, duplicate-add, remove, and repeated-remove from separate store instances; assert the returned traveler set and graph node count.
- [ ] **Step 3: Write the upgrade runbook.** Explain backup, identification of top-level-only legacy travels, reconstruction from an external source, and graph inspection queries. Do not claim lost in-memory itinerary data can be migrated.
- [ ] **Step 4: Commit.** Commit the participation and operations slice with `feat: persist travel participation`.

### Task 5: Full verification and audit evidence

**Files:** Modify `docs/audit/project-audit-2026-09-29.md`.

- [ ] **Step 1: Run `mvnw.cmd -B -ntp verify`.** Record test counts and six-module reactor status.
- [ ] **Step 2: Run base, secure, and scale Compose `config --quiet`.** Record exit codes and warnings. The in-process harness supplies graph runtime evidence because this host has no Docker daemon.
- [ ] **Step 3: Review the diff.** Run `git diff --check`; request a read-only review, fix critical/important findings, then rerun verification.
- [ ] **Step 4: Update audit and push.** Record verified travel improvements and remaining contract/deployment limits, commit the audit update, and push the same `build-security-data-fixes` branch to `origin`.

## Plan self-review

- Destination, aggregate, participation, legacy migration, and verification requirements each have an explicit task.
- Every graph write uses managed transactions and every read comes from Neo4j in the infrastructure profile.
- Tests use an in-process database and avoid a Docker dependency on this host.
