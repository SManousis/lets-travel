# Phase 5 verification: Elasticsearch search and autocomplete

Verified on 2026-10-07 on branch `phase-5-6`: base commit `1757a07` plus the uncommitted
Phase 5 changes. Environment:

- macOS with Docker Desktop 29.8 (8 CPUs, 8 GB for Docker);
- OpenJDK 21, Node 24 and the Maven Wrapper.

The design is in [search](../search.md) and the contract in [search.yaml](../api/search.yaml).

Subject: *Integrate an Elasticsearch-based travel search with autocomplete for smooth,
dynamic querying across all travel details, ensuring swift and accurate results.*

## Design in one paragraph

- **Source of truth.** travel-service (Neo4j) owns travels. search-service keeps a
  derived Elasticsearch copy of the bookable ones: published, starting in the future,
  with places left.
- **Sync.** After each committed travel change, travel-service posts the travel ID to
  search-service. Changes include publish, unpublish, edit, delete, and reserving or
  releasing a place. search-service fetches the travel's current state and indexes or
  removes it. This is idempotent and version-guarded.
- **Repair.** A full rebuild into a new versioned index, followed by an atomic alias
  switch, runs at start-up, every 5 minutes, and on demand. It repairs any drift.
- **Fallback.** If Elasticsearch is down, search answers `503 SEARCH_UNAVAILABLE`
  within 5 seconds. The UI then falls back to the travel-service list.

## Automated checks

All commands were run from the repository root, with Maven prefixed by
`env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=...`.
Logs were written to files and grepped.

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp clean verify` | PASS. All nine modules `BUILD SUCCESS`; 304 tests, 0 failures, 0 errors, 0 skipped. Per module: search-service 29, travel-service 40, api-gateway 56. The search-service count includes 20 tests against a real Elasticsearch 9.4.5 Testcontainer. |
| `npm run lint:api` | PASS (exit 0). All 8 contracts are valid; the 1 ignored problem is pre-existing and unchanged. |
| `npm --prefix admin-ui run test:ci` | PASS. 6 files, 28 tests (9 new in `TravelSearch.test.tsx`). |
| `npm --prefix admin-ui run lint` | PASS (exit 0). |
| `npm --prefix admin-ui run build` | PASS (exit 0). |
| `docker compose -f infra/compose/compose.yml -f infra/compose/compose.ci.yml config --quiet` | PASS (exit 0). |

The compose file now wires search:

- **travel-service** gets `SEARCH_SERVICE_URL`.
- **search-service** gets `TRAVEL_SERVICE_URL`, `AUTH_SERVICE_URL` and the token
  secrets, and waits until travel-service is healthy.
- **api-gateway** gets `SEARCH_SERVICE_URL`. It waits only until search-service has
  *started*, so an Elasticsearch outage cannot keep the platform down.

The Elasticsearch tests use `@Testcontainers(disabledWithoutDocker = true)`, the same
image as compose, with security off inside the test. On a machine without Docker they
are skipped, and `SearchUnavailableTest` still checks the outage path.

## What the tests prove

### `TravelSearchIntegrationTest` (search-service, real Elasticsearch)

travel-service is a stand-in serving the representative data set in
[search §9](../search.md#9-representative-data-set-and-expected-results). The index is
rebuilt before each test.

| Requirement | Test |
| --- | --- |
| Versioned mapping and alias; rebuild creates `travels-v1-<ts>`, switches the alias, deletes the old index | `rebuildCreatesAVersionedIndexBehindTheAliasAndDropsTheOldOne` |
| Search across all details: activity, accommodation, transport, description, manager, country, destination | `searchMatchesEveryTravelDetail` |
| Case- and accent-insensitive (`krakow` = `KRAKÓW` = Kraków, `chania` = Chaniá) | `searchIgnoresCaseAndAccents` |
| Exact before fuzzy, prefix while typing, typo tolerance | `exactAndPrefixMatchesRankAboveFuzzyOnes` |
| Unpublished travels never returned | `neverReturnsUnpublishedTravels` |
| Filters: country, transport, price with currency, start-date range, and sorting | `filtersNarrowTheResults` |
| Deterministic empty query, pagination, freshness timestamp | `emptyQueryIsDeterministicAndPaged` |
| Result fields and plain-text highlights | `resultsCarryWhatTheListShowsAndPlainTextHighlights` |
| Autocomplete: TRAVEL with dates and first stop, plus ACTIVITY, COUNTRY and DESTINATION; drafts excluded; typo fallback | `autocompleteSuggestsTitlesDestinationsCountriesAndActivities` |
| Autocomplete limit, and an answer in under 1 s | `autocompleteHonoursTheLimitAndAnswersQuickly` |
| Malformed or abusive input rejected with `400 INVALID_QUERY` and `fieldErrors` | `rejectsMalformedOrAbusiveQueries` |
| Elasticsearch syntax is treated as plain text | `elasticsearchQuerySyntaxIsTreatedAsPlainText` |
| Updates applied; duplicate notifications are idempotent | `notifiedChangesAreAppliedAndRepeatsChangeNothing` |
| New publication becomes searchable | `newlyPublishedTravelBecomesSearchableAfterItsNotification` |
| Unpublish and delete disappear immediately, from search and autocomplete | `unpublishedOrDeletedTravelsDisappearImmediately` |
| A stale or out-of-order write never overwrites newer data (`external_gte`) | `anOlderVersionNeverOverwritesANewerOne` |
| Replay: rebuild repairs drift from lost notifications (Admin reindex) | `rebuildRepairsDriftLeftByLostNotifications` |
| Operational view: alias, mapping version, documents, last rebuild; Admin only | `adminStatusShowsLagAndLastRebuild` |
| Outage: the container is paused. Search and autocomplete give `503 SEARCH_UNAVAILABLE` with `Retry-After: 30`, and the change stays queued and visible as failing. After unpause the queued change is applied. | `elasticsearchOutageGivesSafe503AndRecoversWithQueuedChanges` |
| Latency under load | `searchAndAutocompleteStayFastUnderLoad` (below) |

### Latency under the documented load

The load test adds 1,000 extra travels (1,005 indexed). It then sends 400 requests from 10
concurrent clients, directly to search-service with token validation. The requests are
an even mix of free-text search, multi-word search, filtered and sorted search, empty
browse, and four autocomplete queries, one of them with a typo.

```
search-load: requests=400 p50=46ms p95=368ms max=552ms
```

The test fails if p95 reaches 1 s or the slowest request reaches 5 s. Every audited
search action is far below the 5-second limit.

### Other tests

- **`SearchUnavailableTest`** (no Docker needed): with no Elasticsearch, both endpoints
  return 503 `SEARCH_UNAVAILABLE` and `Retry-After`. Notifications still return `202`
  (a wrong service token gets `403`), stay queued, and are listed as failing.
- **`AuthorizationMatrixTest`** (search-service): lists all 5 endpoints.
  - search and autocomplete: TRAVELER;
  - status and reindex: ADMIN;
  - the index notification: INTERNAL.
- **`TravelSearchFeedTest`** (travel-service):
  - create, publish, reserve and unpublish each send a notification carrying the
    service token;
  - `/internal/search/travels/{id}` carries every searchable detail and is 404 for a
    deleted travel and 403 without the token;
  - the paged rebuild feed lists only bookable travels and rejects a size above 500.
- **`SearchIndexNotifierTest`** (travel-service): an unreachable search-service does
  not fail a travel change, and browsing still lists the travel. This is core browsing
  surviving a search outage.
- **Gateway** (`GatewayRoleMatrixTest`, `GatewayRoutingTest`):
  - `/api/search/**` routes to search-service;
  - `/api/search/admin/**` is Admin-only and the rest needs any signed-in role;
  - `/internal/search/**` and `/api/search-extra` are not routed (404).
- **UI** (`TravelSearch.test.tsx`):
  - one autocomplete request after debounced typing, and none below 2 characters;
  - filters sent to the API, highlights shown;
  - picking a place suggestion runs a search, and picking a travel opens it;
  - fallback to `/api/travels` with the "search is temporarily limited" notice on 503;
  - validation error shown;
  - paging;
  - a result opens the travel detail with an enabled **Subscribe** button.

## Auditor demo script (about 10 minutes)

Start the stack with `docker compose -f infra/compose/compose.yml up -d --build`. Open
`http://127.0.0.1:5173`. Then:

1. **Data.**
   - Sign in as a travel manager.
   - Create and publish two travels with different destinations, activities, hotels and
     transport. For example: "Crete Island Escape" (Chaniá, Greece, "Sea kayaking",
     FERRY), and "Swiss Alps by Train" (Zürich, Switzerland, "Glacier hike", TRAIN).
   - Leave a third travel as a draft.
2. **Search across details.**
   - Sign in as a traveler.
   - In **Search travels**, search `kayaking`, then `ferry`, then the hotel name. Each
     finds the Crete travel.
   - Search `zurich` (no accent). It finds the Alps travel.
   - The draft never appears.
3. **Autocomplete.**
   - Type `cre` slowly. Suggestions appear after a short pause, showing the travel with
     its dates and first stop.
   - Type `kay`. An *Activity* suggestion appears.
   - Pick a country suggestion. It runs a search.
   - Pick a travel suggestion. Its details open with **Subscribe**.
4. **Filters and sorting.** Set Country `Greece`, or Max price with currency `EUR`, or
   Sort by price. Show that the results change as expected.
5. **Sync.**
   - As the manager, unpublish the Crete travel.
   - As the traveler, search again within a second or two. It is gone.
   - Publish it again, and it is back.
6. **Explain and show the operational view.**
   - Sign in as an Admin and call `GET /api/search/admin/status`, for example with
     `curl -H "Authorization: Bearer <admin token>" http://127.0.0.1:8080/api/search/admin/status`.
   - Point out `currentIndex` (`travels-v1-…`), `documents` and `pendingChanges` (lag).
   - `POST /api/search/admin/reindex` creates a new `travels-v1-…` index and switches
     the alias, while searches keep working.
7. **Fallback.**
   - Run `docker compose -f infra/compose/compose.yml stop elasticsearch`.
   - Search again. The UI shows *"Search is temporarily limited…"* and lists the
     available travels from travel-service. Opening one and subscribing still works.
   - `curl` the search endpoint. It returns `503 SEARCH_UNAVAILABLE` with
     `Retry-After: 30`.
   - Run `docker compose … start elasticsearch`. Within about 30 seconds the next
     rebuild (or the queued changes) restores normal search.

For the comprehension questions (analyzers, inverted index, autocomplete, scaling,
consistency, fallback), use [search §10](../search.md#10-questions-an-auditor-may-ask).

## Not covered in this session

- **The live demo above was not run against the full compose stack in this session.**
  The automated tests cover each step at service level, against a real Elasticsearch.
- The `search.index.*` metric gauges are registered, but the actuator only exposes
  `health`. Lag and failures are read from the Admin status endpoint.
