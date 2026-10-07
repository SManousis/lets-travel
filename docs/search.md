# Travel search and autocomplete (Phase 5)

Travelers find travels with a search box. As they type, it suggests travels, places and
activities, and they can filter the results by country, transport, dates and price.
search-service and Elasticsearch power both. This page explains how it works, in the
order an auditor usually asks.

- Code: `search-service/src/main/java/com/travelplan/search/`
- Index mapping: `search-service/src/main/resources/elasticsearch/travel-index-v1.json`
- API contract: [api/search.yaml](api/search.yaml)
- UI: `admin-ui/src/features/search/TravelSearch.tsx`
- Tests: `search-service/src/test/.../TravelSearchIntegrationTest.java`, which runs
  against a real Elasticsearch in Docker

## 1. Where search sits

```
browser ──► api-gateway ──► search-service ──► Elasticsearch  (index "travels")
                 │                 ▲
                 │                 │ "travel X changed" / fetch travel X / fetch all bookable
                 └──► travel-service (Neo4j, source of truth)
```

- **travel-service owns the travels** in Neo4j. Elasticsearch only holds a copy, made
  for fast searching. If the copy is lost, nothing is lost: it is rebuilt from
  travel-service.
- **The index holds only bookable travels:** published, starting in the future, with
  places left. Drafts, unpublished, cancelled, past and full travels are never in it,
  so a traveler can never find them.
- **Everything goes through the gateway:**
  - Any signed-in role may call `GET /api/search/travels` and
    `GET /api/search/autocomplete`.
  - Only Admins may call `/api/search/admin/**`.
  - The gateway never routes `/internal/**`.

## 2. How indexing works

Elasticsearch stores one JSON **document** per travel. The document holds every detail
a traveler might search for:

- title and description;
- destinations and countries, in itinerary order;
- activities (name, description, date);
- accommodations (name, address);
- transport (mode, departure, arrival);
- the manager's name;
- dates, duration, price and currency;
- capacity and remaining places;
- status and version.

The mapping file says how each field is stored. It is `dynamic: strict`, so a field
that is not in the mapping is rejected instead of being guessed.

When a document is written, each **text** field goes through an **analyzer**. The
analyzer cuts the text into words (tokens) and normalizes them. The words go into an
**inverted index**: a dictionary from each word to the travels that contain it, like
the index at the back of a book. A search looks words up in this dictionary instead of
reading every travel. That is why search stays fast as the data grows.

### Analyzers we use

| Name | Steps | Used for | Example |
| --- | --- | --- | --- |
| `folded` | standard tokenizer → lowercase → asciifolding | all text fields, and the search text itself | `"Chaniá Harbour"` → `chania`, `harbour` |
| `autocomplete` | `folded`, then an **edge n-gram** filter (1–20 characters) | the `.prefix` sub-fields of title, destination, country and activity name | `"Kayaking"` → `k`, `ka`, `kay`, … `kayaking` |
| `folded_keyword` (normalizer) | lowercase → asciifolding, on the **whole** value | the `.exact` sub-fields, used by filters | `"Zürich"` → `zurich` |

- **Case and accents.** The same folding runs when we index and when we search. So
  `krakow`, `KRAKÓW` and `Kraków` all find the same travel.
- **Prefixes.** The edge n-gram filter stores every word's beginnings at indexing time.
  A prefix lookup ("kay") is then an ordinary, fast word lookup.
  - The `.prefix` fields search with `folded`, not `autocomplete`, so the typed text
    is not cut into n-grams itself.
- **Filters.** Filters such as `country=greece` need the whole value, not single
  words. That is what the keyword sub-fields with the normalizer are for. Prices are a
  `scaled_float` (cents precision) and dates are `date` fields, so ranges are cheap.

### Versioned index behind an alias

- The real index has a versioned name: `travels-v1-<timestamp>`. Here `v1` is the
  mapping version.
- Searches and single writes use the **alias** `travels`, which points at exactly one
  real index.
- A rebuild creates a new index, fills it, refreshes it, and then moves the alias in
  **one atomic `_aliases` request**. Searches use the old index until that moment, so
  there is no downtime and no half-built index.
- The old index is then deleted.
- When the mapping changes, add `travel-index-v2.json` and bump `MAPPING_VERSION`.
  The next rebuild moves everyone over.

## 3. How a search runs

`GET /api/search/travels?q=…&country=…&transportMode=…&startFrom=…&startTo=…&minPrice=…&maxPrice=…&currency=…&sort=…&page=…&size=…`

`TravelSearch.search` builds a `bool` query:

- **must: the text, in three tiers.** One travel can match several tiers, and the
  scores add up, so better matches rank higher:
  1. the whole text as an exact **phrase** (boost 6);
  2. every word as a **prefix** (`.prefix` fields, boost 3);
  3. every word with **typo tolerance**: `fuzziness: AUTO` allows 1 typo for 3–5
     letters and 2 typos above that (boost 1).

  This is why exact and prefix matches rank above fuzzy ones. For example, `cretan`
  returns "Cretan Cooking Week" first and then "Crete Island Escape", which is two
  letters away.

  Field weights matter too. A hit in the title (×4) or destination (×3) counts more
  than one in an accommodation address. An empty `q` is `match_all`.
- **filter:** `bookable = true`, start date after today, and each filter given. Filters
  only narrow the results; they do not change the score.
- **sort:**
  - with text, by relevance by default;
  - otherwise by start date;
  - or by price, if asked.

  Every sort ends with start date and travel ID, so equal results always come back in
  the same order. An empty query is therefore deterministic.
- **paging:** `size` is 1–50 and `(page+1)×size` is at most 1000. Deep paging is
  expensive in Elasticsearch, so we refuse it.
- **highlights:** Elasticsearch returns the matching piece of the details, such as
  "Sea kayaking", so the user sees why a travel matched. Tags are disabled, so the
  highlights are plain text, and the UI shows them as text, never as HTML.

### Safety: no query syntax from clients

- The client's text only ever goes in as the *value* of `match`-style queries. We never
  use `query_string`, so `title:crete OR *` is just text and matches nothing.
- Every parameter is checked before Elasticsearch is called:
  - lengths are limited;
  - control characters are rejected;
  - names must be letters;
  - dates and enums are parsed;
  - price ranges must be ordered and need a currency.

  A bad request gets `400 INVALID_QUERY`, with one `fieldErrors` entry per problem.

## 4. How autocomplete works

- **In the browser** (`TravelSearch.tsx`):
  - The browser waits until the user has stopped typing for **250 ms** (debounce), and
    asks only from **two characters** up.
  - Only the answer to the latest keystroke is shown, so a slow answer cannot replace
    a newer one.
- **On the server** (`TravelSearch.autocomplete`): one small query on the `.prefix`
  fields (plus a fuzzy fallback) returns the 20 best bookable travels. From them we
  build at most `limit` suggestions (default 8, maximum 10):
  - **TRAVEL**: the title, with the dates and first destination as context. This is
    how two similarly named travels can be told apart. Picking it opens the travel
    with its Subscribe button.
  - **DESTINATION**, **COUNTRY**, **ACTIVITY**: picking one puts it in the box and
    runs a search.

  The order is exact match first, then prefix match, then typo-only match. Within a
  group the Elasticsearch score decides.
- **Speed:** autocomplete reads a single small index with prefix tokens already
  stored, so it answers in milliseconds. Measured numbers are in
  [the verification](audit/phase-5-verification.md).

## 5. Consistency between PostgreSQL, Neo4j and Elasticsearch

Each piece of data has exactly **one owner**, and no request writes to two stores at
once:

| Store | Owner | Holds |
| --- | --- | --- |
| Neo4j | travel-service | travels, itineraries, capacity and participants (**source of truth for search**) |
| PostgreSQL | auth, user, payment, engagement services | accounts, profiles, payments, subscriptions |
| Elasticsearch | search-service | a **derived copy** of bookable travels |

Subscriptions live in PostgreSQL, but the *places* they hold are taken atomically in
Neo4j by travel-service (Phase 3). So the remaining places that search shows also come
from travel-service. Search never reads PostgreSQL. The manager's name comes from
user-service when the travel is indexed.

### How the copy follows the source

1. **Change notification.** After each committed travel change, travel-service sends
   `POST /internal/search/index/travels/{id}` with the internal service token
   (`SearchIndexNotifier`). This covers create, update, publish, unpublish, delete,
   and reserve or release a place.
   - The message holds only the ID.
   - It is sent on a background thread and is never retried by travel-service. A
     failure only logs a warning, so travel writes never depend on search.
2. **Apply.** search-service queues the ID. Once a second (`TravelIndexer`) it takes
   each queued ID, asks `GET /internal/search/travels/{id}` for the travel's
   **current** state, and then:
   - indexes the travel if it is published and bookable;
   - removes it if not, or if the travel is gone (404).

   Unpublish and delete therefore take effect within about a second. Writes use
   `refresh=wait_for`, so they are searchable when the call returns.
3. **Idempotent and ordered.**
   - Applying an ID means "make the index equal to the source for this travel". Doing
     it twice, or for a duplicate notification, gives the same result.
   - IDs are applied one at a time on one thread.
   - Every write carries the travel's version (`version_type=external_gte`).
     Elasticsearch refuses a write older than what it already stores. Two
     search-service replicas racing, or a late retry, can never put old data back.
4. **Retries and failures.** If Elasticsearch or travel-service is down, the ID stays
   queued and is retried every second. The failure (attempt count, last error, time)
   is shown in `GET /api/search/admin/status`.
5. **Full rebuild (repairs drift).** At start-up, and then every 5 minutes
   (`SEARCH_REBUILD_INTERVAL`), search-service rebuilds the index:
   - it reads all bookable travels from `GET /internal/search/travels` (paged);
   - it builds a fresh versioned index and switches the alias.

   A failed rebuild is retried every 30 seconds. An Admin can start one at once with
   `POST /api/search/admin/reindex`.

   The rebuild repairs everything a notification could miss:
   - search-service was down when the notification was sent;
   - a destination was renamed;
   - a deleted user's places were freed;
   - a travel's start date passed.

   Notifications that arrive during a rebuild wait in the queue and are applied to the
   new index right after.

In short, search is **eventually consistent**. Normally it is about a second behind
Neo4j, and never more than one rebuild interval behind. The index can always be
compared with travel-service and replaced from it.

**Why not an event feed or broker?**
- [events](events.md) describes the planned outbox feeds. The Neo4j `TravelEvent`
  markers do not have a feed position yet, and reservations do not write one.
- Notify-and-fetch plus a rebuild needs no broker, no cursor storage and no event
  payload parsing.
- Its weakness, a lost notification, is exactly what the periodic rebuild repairs.

## 6. Scaling and independence

- **Separate service, separate store.**
  - search-service has its own Elasticsearch, and no other service reads it.
  - travel-service does not need search: if search-service or Elasticsearch is down,
    travel writes and `GET /api/travels` work as before
    (`SearchIndexNotifierTest`).
  - In compose, the gateway only waits for search-service to *start*, not to be
    healthy, so the platform comes up even when Elasticsearch is down.
- **search-service is stateless.** All its data is in Elasticsearch, and the queue is
  only a short buffer. `infra/compose/compose.scale.yml` runs 2 replicas behind the
  same name. A notification reaches one replica, which indexes it into the shared
  index. A queued ID lost in a crash is repaired by the next rebuild.
- **Elasticsearch scales on its own.** An index is split into **shards** (pieces
  spread over nodes) and each shard can have **replicas** (copies on other nodes).
  - More nodes and shards hold more data.
  - More replicas serve more searches in parallel and survive a node failure.
  - Locally we run one node with 1 shard and 0 replicas (`number_of_replicas: 0`,
    since a single node cannot hold a replica).
  - In production this becomes a 3-node cluster with 1 replica. Only the mapping
    settings and the cluster change; the code does not.
- **Bounded cost per request:**
  - every Elasticsearch call has a deadline (`search.elasticsearch.request-timeout`,
    5 s);
  - page size and depth are capped;
  - autocomplete reads at most 20 documents;
  - rebuild writes go in bulk chunks of 500.

## 7. Fallback when something fails

| What fails | What happens |
| --- | --- |
| Elasticsearch down or hanging | Search and autocomplete answer **`503 SEARCH_UNAVAILABLE`** with `Retry-After: 30` within the 5 s deadline, never an error page or a hang. The UI shows "Search is temporarily limited", loads the plain list from travel-service (`GET /api/travels`) and matches the name or description in the browser. Booking still works. Changes keep queuing and are applied when Elasticsearch is back (tested by pausing the container). |
| search-service down | Same UI fallback (the gateway answers 5xx). travel-service notifications fail silently; the rebuild at start-up catches up. |
| travel-service down | Search keeps answering from the index. Queued changes and rebuilds retry. A manual reindex answers `503 DEPENDENCY_UNAVAILABLE`. |
| user-service down | The travel is indexed without the manager name (`managerName: null`). |

## 8. Operations

- `GET /api/search/admin/status` (Admin) returns:
  - the alias and the current index;
  - the mapping version;
  - whether Elasticsearch answers;
  - the document count;
  - **`pendingChanges`**, the sync lag;
  - **`failingChanges`**, the failed indexing events;
  - `lastAppliedAt` and the last rebuild.
- `POST /api/search/admin/reindex` (Admin) rebuilds now.
- Metrics: the gauges `search.index.pending` and `search.index.failing`.
- The `infra` profile's health check includes Elasticsearch, so a cluster outage shows
  as DOWN on the private management port.

## 9. Representative data set and expected results

`TravelSearchIntegrationTest` loads these travels before every test and checks the
results below against a real Elasticsearch 9.4.5:

| Travel | Destinations (country) | Details used by tests |
| --- | --- | --- |
| Crete Island Escape | Chaniá, Heraklion (Greece) | Sea kayaking, Samaria Gorge hike, Harbour Hotel, FERRY Piraeus→Chania, €450, starts in 30 days |
| Cretan Cooking Week | Rethymno (Greece) | Cooking class, PLANE, €600, in 40 days |
| Lisbon City Break | Lisbon (Portugal) | Tram 28 tour, Fado night, "pastel de nata", manager Maria Papadopoulou, €300, in 20 days |
| Swiss Alps by Train | Zürich, Zermatt (Switzerland) | Glacier hike, TRAIN, €1200, in 60 days |
| Kraków Christmas Markets | Kraków (Poland) | Christmas market walk, BUS, €250, in 50 days |
| Hidden Crete Draft | Chaniá (Greece) | **unpublished**, so it must never appear |

| Input | Expected (in order) | Shows |
| --- | --- | --- |
| `q=kayaking`, `Harbour Hotel`, `Piraeus` | Crete Island Escape | activity, accommodation, transport are searched |
| `q=Papadopoulou`, `Portugal`, `pastel de nata` | Lisbon City Break | manager, country, description |
| `q=krakow` / `KRAKÓW`, `q=zurich` | Kraków…, Swiss Alps… | case and accents ignored |
| `q=cretan` | Cretan Cooking Week, Crete Island Escape | exact before fuzzy |
| `q=lisb`, `q=lisbn` | Lisbon City Break | prefix and typo |
| `q=hidden` | nothing | unpublished never searchable |
| `country=greece` | Crete…, Cretan… | filter, ordered by start date |
| `maxPrice=400&currency=EUR&sort=PRICE_ASC` | Kraków (250), Lisbon (300) | price filter and sort |
| empty query | Lisbon, Crete, Cretan, Kraków, Alps | deterministic start-date order |
| autocomplete `kay` / `port` / `zur` | ACTIVITY Sea kayaking / COUNTRY Portugal / DESTINATION Zürich | suggestion types |
| autocomplete `cre` | both Crete travels with dates and first stop, not the draft | context, visibility |

## 10. Questions an auditor may ask

- **How does Elasticsearch make search fast?** It keeps an inverted index, a dictionary
  from each normalized word to the travels containing it. A search is a few
  dictionary lookups plus scoring (BM25), not a scan of every travel.
- **What is an analyzer?** The steps that turn text into searchable words. Ours split
  on word boundaries, lowercase, and remove accents. For autocomplete they also store
  each word's prefixes (edge n-grams).
- **How does autocomplete stay relevant and fast?** Prefixes are stored at indexing
  time, so a keystroke is a cheap lookup. The browser debounces (250 ms, 2+
  characters). Results are capped (≤10). Exact matches rank before prefixes, and
  prefixes before typos. Each travel suggestion shows its dates and first destination.
- **How is the index kept consistent with Neo4j?** travel-service notifies after every
  commit. search-service re-reads the current travel and indexes or removes it, which
  is idempotent and version-guarded. A full rebuild every 5 minutes, or on demand,
  replaces the index from the source and repairs anything missed. PostgreSQL is not
  involved directly: the places subscriptions take are counted in Neo4j by
  travel-service.
- **What if Elasticsearch fails?** Search answers a safe 503 within 5 seconds. The UI
  falls back to the travel-service list, so browsing and booking keep working, and the
  index catches up when Elasticsearch is back.
- **How does it scale?** search-service is stateless and runs as several replicas.
  Elasticsearch scales by adding nodes, shards (more data) and replicas (more reads,
  failover), without code changes.
