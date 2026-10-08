package com.travelplan.search;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import com.travelplan.common.testing.AuthorizationMatrix;
import com.travelplan.search.index.TravelDocument;
import com.travelplan.search.index.TravelDocument.ActivityText;
import com.travelplan.search.index.TravelDocument.Place;
import com.travelplan.search.index.TravelDocument.StayText;
import com.travelplan.search.index.TravelDocument.TransportText;
import com.travelplan.search.index.TravelIndex;
import com.travelplan.search.index.TravelIndexer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search, autocomplete, index updates, rebuilds and an Elasticsearch outage against a real
 * Elasticsearch (same image as infra/compose). travel-service is a small stand-in serving the
 * representative data set below, so each test controls exactly what the source of truth says.
 * Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "security.internal-service-token=test-internal",
        // The tests apply queued changes and rebuild explicitly, so the schedules stay out of the way.
        "search.sync.apply-initial-delay=PT1H", "search.sync.rebuild-initial-delay=PT1H",
        // Room for a busy CI host; the outage test still gets its 503 well inside the client's 30s.
        "search.elasticsearch.request-timeout=10s"})
class TravelSearchIntegrationTest {
    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:9.4.5")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("node.store.allow_mmap", "false")
            // CI starts this on the shared deploy host, which can be slow; the 60s default was too short.
            .withStartupTimeout(Duration.ofMinutes(5));

    private static final HttpServer AUTH = AuthorizationMatrix.activeSessionsAuthServer();
    /** What the travel-service stand-in currently holds, by travel ID. */
    private static final Map<UUID, TravelDocument> SOURCE = new ConcurrentHashMap<>();
    private static final HttpServer TRAVEL_SERVICE = travelServiceStandIn();

    static final UUID CRETE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID CRETAN_COOKING = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID LISBON = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID ALPS = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID KRAKOW = UUID.fromString("00000000-0000-0000-0000-000000000005");
    static final UUID HIDDEN = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Autowired TravelIndexer indexer;
    @Autowired TravelIndex index;
    @Autowired AccessTokenCodec codec;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
        registry.add("services.travel.url", () -> "http://127.0.0.1:" + TRAVEL_SERVICE.getAddress().getPort());
    }

    @AfterAll static void stop() {
        AUTH.stop(0);
        TRAVEL_SERVICE.stop(0);
    }

    /** The representative data set (also listed in docs/search.md), rebuilt fresh for every test. */
    @BeforeEach void loadRepresentativeTravels() {
        SOURCE.clear();
        put(travel(CRETE, "Crete Island Escape", "A slow week on Crete's wild south coast.", 30, "450.00",
                List.of(new Place("Chaniá", "Greece"), new Place("Heraklion", "Greece")),
                List.of(new ActivityText("Sea kayaking", "Paddle under the white cliffs", null),
                        new ActivityText("Samaria Gorge hike", "Full-day canyon walk", null)),
                List.of(new StayText("Harbour Hotel", "1 Venetian Port, Chania")),
                List.of(new TransportText("FERRY", "Piraeus", "Chania")), "Eleni Markou"));
        put(travel(CRETAN_COOKING, "Cretan Cooking Week", "Learn island recipes from local families.", 40, "600.00",
                List.of(new Place("Rethymno", "Greece")),
                List.of(new ActivityText("Cooking class", "Olive oil, herbs and bread", null)),
                List.of(new StayText("Olive Farm Guesthouse", "Rethymno hills")),
                List.of(new TransportText("PLANE", "Athens", "Heraklion")), "Eleni Markou"));
        put(travel(LISBON, "Lisbon City Break", "Hills, trams and pastel de nata.", 20, "300.00",
                List.of(new Place("Lisbon", "Portugal")),
                List.of(new ActivityText("Tram 28 tour", "Ride through Alfama", null),
                        new ActivityText("Fado night", "Traditional music dinner", null)),
                List.of(new StayText("Alfama Apartments", "Rua dos Remédios")),
                List.of(new TransportText("PLANE", "Athens", "Lisbon")), "Maria Papadopoulou"));
        put(travel(ALPS, "Swiss Alps by Train", "Panoramic railways and mountain villages.", 60, "1200.00",
                List.of(new Place("Zürich", "Switzerland"), new Place("Zermatt", "Switzerland")),
                List.of(new ActivityText("Glacier hike", "Guided walk on the Gorner glacier", null)),
                List.of(new StayText("Matterhorn Lodge", "Zermatt village")),
                List.of(new TransportText("TRAIN", "Zürich", "Zermatt")), "Nikos Alexiou"));
        put(travel(KRAKOW, "Kraków Christmas Markets", "Winter lights in the old town.", 50, "250.00",
                List.of(new Place("Kraków", "Poland")),
                List.of(new ActivityText("Christmas market walk", "Mulled wine and pierogi", null)),
                List.of(new StayText("Old Town Inn", "Rynek Główny")),
                List.of(new TransportText("BUS", "Vienna", "Kraków")), "Nikos Alexiou"));
        put(unpublished(travel(HIDDEN, "Hidden Crete Draft", "Not yet published.", 35, "100.00",
                List.of(new Place("Chaniá", "Greece")), List.of(), List.of(), List.of(), "Eleni Markou")));
        indexer.rebuild();
    }

    // --- Index layout -------------------------------------------------------------------------------------

    @Test void rebuildCreatesAVersionedIndexBehindTheAliasAndDropsTheOldOne() throws Exception {
        String first = indexer.status().currentIndex();
        assertThat(first).startsWith("travels-v1-");
        assertThat(indexer.status().documents()).isEqualTo(5);

        TravelIndexer.Rebuild again = indexer.rebuild();
        assertThat(again.index()).isNotEqualTo(first);
        assertThat(indexer.status().currentIndex()).isEqualTo(again.index());
        assertThat(again.documents()).isEqualTo(5);
        assertThat(index.currentIndex()).isEqualTo(again.index());
        assertThat(searchIds("q=crete")).isNotEmpty(); // the old index is gone, searches use the new one
    }

    // --- Search -------------------------------------------------------------------------------------------

    @Test void searchMatchesEveryTravelDetail() throws Exception {
        assertThat(searchIds("q=kayaking")).containsExactly(CRETE);               // activity name
        assertThat(searchIds("q=Harbour+Hotel")).containsExactly(CRETE);           // accommodation
        assertThat(searchIds("q=Piraeus")).containsExactly(CRETE);                 // transport departure
        assertThat(searchIds("q=fado")).containsExactly(LISBON);                   // activity
        assertThat(searchIds("q=pastel+de+nata")).containsExactly(LISBON);         // description
        assertThat(searchIds("q=Papadopoulou")).containsExactly(LISBON);           // manager name
        assertThat(searchIds("q=Portugal")).containsExactly(LISBON);               // country
        assertThat(searchIds("q=Zermatt")).containsExactly(ALPS);                  // destination
        assertThat(searchIds("q=glacier+walk")).containsExactly(ALPS);             // activity description
        assertThat(searchIds("q=submarine")).isEmpty();
    }

    @Test void searchIgnoresCaseAndAccents() throws Exception {
        assertThat(searchIds("q=krakow")).containsExactly(KRAKOW);
        assertThat(searchIds("q=" + encode("KRAKÓW"))).containsExactly(KRAKOW);
        assertThat(searchIds("q=chania")).containsExactly(CRETE);
        assertThat(searchIds("q=zurich")).containsExactly(ALPS);
    }

    @Test void exactAndPrefixMatchesRankAboveFuzzyOnes() throws Exception {
        // "cretan" is a whole word of one title; "Crete" is two typos away and only matches fuzzily.
        assertThat(searchIds("q=cretan")).containsExactly(CRETAN_COOKING, CRETE);
        assertThat(searchIds("q=crete")).containsExactly(CRETE);
        // A prefix while typing.
        assertThat(searchIds("q=lisb")).containsExactly(LISBON);
        // A typo still finds the travel.
        assertThat(searchIds("q=lisbn")).containsExactly(LISBON);
        assertThat(searchIds("q=" + encode("swis alps"))).containsExactly(ALPS);
    }

    @Test void neverReturnsUnpublishedTravels() throws Exception {
        assertThat(searchIds("q=hidden")).isEmpty();
        assertThat(searchIds("")).doesNotContain(HIDDEN).hasSize(5);
    }

    @Test void filtersNarrowTheResults() throws Exception {
        assertThat(searchIds("country=greece")).containsExactly(CRETE, CRETAN_COOKING);
        assertThat(searchIds("country=Switzerland&q=hike")).containsExactly(ALPS);
        assertThat(searchIds("transportMode=train")).containsExactly(ALPS);
        assertThat(searchIds("maxPrice=400&currency=EUR&sort=PRICE_ASC")).containsExactly(KRAKOW, LISBON);
        assertThat(searchIds("minPrice=500&currency=EUR&sort=PRICE_DESC")).containsExactly(ALPS, CRETAN_COOKING);
        assertThat(searchIds("startFrom=" + LocalDate.now().plusDays(25) + "&startTo=" + LocalDate.now().plusDays(45)))
                .containsExactly(CRETE, CRETAN_COOKING);
    }

    @Test void emptyQueryIsDeterministicAndPaged() throws Exception {
        assertThat(searchIds("")).containsExactly(LISBON, CRETE, CRETAN_COOKING, KRAKOW, ALPS); // by start date
        JsonNode first = get("/api/search/travels?size=2&page=0", Role.TRAVELER).json();
        JsonNode third = get("/api/search/travels?size=2&page=2", Role.TRAVELER).json();
        assertThat(first.get("totalElements").asInt()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(ids(first)).containsExactly(LISBON, CRETE);
        assertThat(ids(third)).containsExactly(ALPS);
        assertThat(first.get("indexedAt").isNull()).isFalse();
    }

    @Test void resultsCarryWhatTheListShowsAndPlainTextHighlights() throws Exception {
        JsonNode hit = get("/api/search/travels?q=kayaking", Role.TRAVELER).json().get("items").get(0);
        assertThat(hit.get("title").asString()).isEqualTo("Crete Island Escape");
        assertThat(hit.get("destinations").get(0).asString()).isEqualTo("Chaniá");
        assertThat(hit.get("countries").get(0).asString()).isEqualTo("Greece");
        assertThat(hit.get("price").decimalValue()).isEqualByComparingTo("450");
        assertThat(hit.get("remainingPlaces").asInt()).isEqualTo(10);
        assertThat(hit.get("managerName").asString()).isEqualTo("Eleni Markou");
        assertThat(hit.get("highlights").get(0).asString()).contains("Sea kayaking").doesNotContain("<");
    }

    // --- Autocomplete -------------------------------------------------------------------------------------

    @Test void autocompleteSuggestsTitlesDestinationsCountriesAndActivities() throws Exception {
        JsonNode cre = suggestions("cre");
        assertThat(cre.get(0).get("type").asString()).isEqualTo("TRAVEL");
        assertThat(texts(cre)).contains("Crete Island Escape", "Cretan Cooking Week").doesNotContain("Hidden Crete Draft");
        JsonNode escape = cre.valueStream().filter(item -> item.get("text").asString().equals("Crete Island Escape"))
                .findFirst().orElseThrow();
        assertThat(escape.get("travelId").asString()).isEqualTo(CRETE.toString());
        assertThat(escape.get("context").asString()).contains(LocalDate.now().plusDays(30).toString()).contains("Chaniá");

        assertThat(first(suggestions("kay"))).isEqualTo("ACTIVITY:Sea kayaking");
        assertThat(first(suggestions("port"))).isEqualTo("COUNTRY:Portugal");
        assertThat(first(suggestions("zur"))).isEqualTo("DESTINATION:Zürich");
        assertThat(first(suggestions("krak"))).startsWith("TRAVEL:Kraków");
        assertThat(texts(suggestions("lisbn"))).contains("Lisbon City Break"); // typo tolerated, ranked last
    }

    @Test void autocompleteHonoursTheLimitAndAnswersQuickly() throws Exception {
        assertThat(get("/api/search/autocomplete?q=cr&limit=1", Role.TRAVELER).json().get("items")).hasSize(1);
        long started = System.nanoTime();
        suggestions("gree");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    // --- Input checks -------------------------------------------------------------------------------------

    @Test void rejectsMalformedOrAbusiveQueries() throws Exception {
        assertThat(get("/api/search/travels?q=" + "a".repeat(101), Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?q=" + encode("crete\u0000"), Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?size=51", Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?page=20&size=50", Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?maxPrice=100", Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?minPrice=500&maxPrice=100&currency=EUR", Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?startFrom=tomorrow", Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?sort=RANDOM", Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/travels?country=" + encode("{\"match_all\":{}}"), Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/autocomplete?q=c", Role.TRAVELER).status()).isEqualTo(400);
        assertThat(get("/api/search/autocomplete?q=crete&limit=50", Role.TRAVELER).status()).isEqualTo(400);
        Response invalid = get("/api/search/travels?size=0", Role.TRAVELER);
        assertThat(invalid.json().get("code").asString()).isEqualTo("INVALID_QUERY");
        assertThat(invalid.json().get("fieldErrors").get(0).get("field").asString()).isEqualTo("size");
    }

    @Test void elasticsearchQuerySyntaxIsTreatedAsPlainText() throws Exception {
        assertThat(get("/api/search/travels?q=" + encode("title:crete OR *"), Role.TRAVELER).status()).isEqualTo(200);
        assertThat(searchIds("q=" + encode("title:crete OR *"))).isEmpty();
        assertThat(get("/api/search/travels?q=" + encode("\"crete"), Role.TRAVELER).status()).isEqualTo(200);
    }

    // --- Keeping the index in step ------------------------------------------------------------------------

    @Test void notifiedChangesAreAppliedAndRepeatsChangeNothing() throws Exception {
        TravelDocument lisbon = SOURCE.get(LISBON);
        put(withPriceAndVersion(lisbon, "280.00", 2));
        assertThat(notify(LISBON)).isEqualTo(202);
        assertThat(notify(LISBON)).isEqualTo(202); // duplicate notification
        indexer.applyPendingChanges();
        indexer.applyPendingChanges();
        JsonNode hit = get("/api/search/travels?q=lisbon", Role.TRAVELER).json().get("items").get(0);
        assertThat(hit.get("price").decimalValue()).isEqualByComparingTo("280");
        assertThat(indexer.status().pendingChanges()).isZero();
        assertThat(indexer.status().documents()).isEqualTo(5);
    }

    @Test void newlyPublishedTravelBecomesSearchableAfterItsNotification() throws Exception {
        UUID rome = UUID.randomUUID();
        put(travel(rome, "Rome in Spring", "Ancient ruins and gelato.", 45, "500.00",
                List.of(new Place("Rome", "Italy")), List.of(), List.of(), List.of(), "Maria Papadopoulou"));
        assertThat(searchIds("q=rome")).isEmpty();
        notify(rome);
        indexer.applyPendingChanges();
        assertThat(searchIds("q=rome")).containsExactly(rome);
    }

    @Test void unpublishedOrDeletedTravelsDisappearImmediately() throws Exception {
        put(unpublished(SOURCE.get(LISBON)));
        notify(LISBON);
        SOURCE.remove(ALPS);
        notify(ALPS);
        indexer.applyPendingChanges();
        assertThat(searchIds("q=lisbon")).isEmpty();
        assertThat(searchIds("q=zermatt")).isEmpty();
        assertThat(texts(suggestions("lisb"))).doesNotContain("Lisbon City Break");
    }

    @Test void anOlderVersionNeverOverwritesANewerOne() throws Exception {
        TravelDocument lisbon = SOURCE.get(LISBON);
        index.put(withPriceAndVersion(lisbon, "199.00", 5));
        index.put(withPriceAndVersion(lisbon, "999.00", 4)); // late, out-of-order write
        JsonNode hit = get("/api/search/travels?q=lisbon", Role.TRAVELER).json().get("items").get(0);
        assertThat(hit.get("price").decimalValue()).isEqualByComparingTo("199");
    }

    @Test void rebuildRepairsDriftLeftByLostNotifications() throws Exception {
        UUID oslo = UUID.randomUUID();
        put(travel(oslo, "Oslo Fjords", "Fjord cruise.", 33, "800.00",
                List.of(new Place("Oslo", "Norway")), List.of(), List.of(), List.of(), "Nikos Alexiou"));
        SOURCE.remove(KRAKOW); // deleted while search-service was not listening
        assertThat(searchIds("q=oslo")).isEmpty();
        assertThat(searchIds("q=krakow")).containsExactly(KRAKOW);

        assertThat(post("/api/search/admin/reindex", Role.ADMIN).status()).isEqualTo(200);

        assertThat(searchIds("q=oslo")).containsExactly(oslo);
        assertThat(searchIds("q=krakow")).isEmpty();
    }

    @Test void adminStatusShowsLagAndLastRebuild() throws Exception {
        JsonNode status = get("/api/search/admin/status", Role.ADMIN).json();
        assertThat(status.get("alias").asString()).isEqualTo("travels");
        assertThat(status.get("mappingVersion").asString()).isEqualTo("v1");
        assertThat(status.get("elasticsearchAvailable").asBoolean()).isTrue();
        assertThat(status.get("documents").asInt()).isEqualTo(5);
        assertThat(status.get("lastRebuild").get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(get("/api/search/admin/status", Role.TRAVELER).status()).isEqualTo(403);
    }

    // --- Outage -------------------------------------------------------------------------------------------

    @Test void elasticsearchOutageGivesSafe503AndRecoversWithQueuedChanges() throws Exception {
        UUID rome = UUID.randomUUID();
        put(travel(rome, "Rome in Spring", "Ancient ruins.", 45, "500.00",
                List.of(new Place("Rome", "Italy")), List.of(), List.of(), List.of(), "Maria Papadopoulou"));
        var docker = ELASTICSEARCH.getDockerClient();
        docker.pauseContainerCmd(ELASTICSEARCH.getContainerId()).exec();
        try {
            Response down = get("/api/search/travels?q=crete", Role.TRAVELER);
            assertThat(down.status()).isEqualTo(503);
            assertThat(down.json().get("code").asString()).isEqualTo("SEARCH_UNAVAILABLE");
            assertThat(down.retryAfter()).isEqualTo("30");
            assertThat(get("/api/search/autocomplete?q=cre", Role.TRAVELER).status()).isEqualTo(503);

            assertThat(notify(rome)).isEqualTo(202); // notifications are still accepted and queued
            indexer.applyPendingChanges();
            TravelIndexer.Status status = indexer.status();
            assertThat(status.elasticsearchAvailable()).isFalse();
            assertThat(status.pendingChanges()).isEqualTo(1);
            assertThat(status.failingChanges()).extracting(TravelIndexer.Failure::travelId).containsExactly(rome);
        } finally {
            docker.unpauseContainerCmd(ELASTICSEARCH.getContainerId()).exec();
        }
        indexer.applyPendingChanges();
        assertThat(searchIds("q=rome")).containsExactly(rome);
        assertThat(indexer.status().failingChanges()).isEmpty();
    }

    // --- Latency under load -------------------------------------------------------------------------------

    /** 1,000 extra travels, 10 concurrent clients, 400 mixed requests: every request well under 5 seconds. */
    @Test void searchAndAutocompleteStayFastUnderLoad() throws Exception {
        String[] words = {"Coast", "Mountain", "City", "Island", "Lake", "Desert", "Forest", "Valley"};
        String[] places = {"Athens", "Paris", "Berlin", "Madrid", "Vienna", "Prague", "Oslo", "Dublin"};
        for (int i = 0; i < 1000; i++) {
            put(travel(UUID.randomUUID(), words[i % 8] + " Tour " + i, "Generated travel number " + i, 10 + i % 300,
                    String.valueOf(100 + i), List.of(new Place(places[i % 8], "Europe")),
                    List.of(new ActivityText(words[(i + 3) % 8] + " walk", "", null)), List.of(), List.of(), "Load Manager"));
        }
        indexer.rebuild();
        String[] requests = {"/api/search/travels?q=coast", "/api/search/travels?q=" + encode("mountain walk"),
                "/api/search/travels?country=europe&sort=PRICE_ASC&currency=EUR&maxPrice=900", "/api/search/travels",
                "/api/search/autocomplete?q=is", "/api/search/autocomplete?q=pra", "/api/search/autocomplete?q=val",
                "/api/search/autocomplete?q=dubln"};
        ExecutorService clients = Executors.newFixedThreadPool(10);
        List<Future<Long>> timings = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String path = requests[i % requests.length];
            timings.add(clients.submit(() -> {
                long started = System.nanoTime();
                assertThat(get(path, Role.TRAVELER).status()).isEqualTo(200);
                return (System.nanoTime() - started) / 1_000_000;
            }));
        }
        List<Long> millis = new ArrayList<>();
        for (Future<Long> timing : timings) millis.add(timing.get());
        clients.shutdown();
        Collections.sort(millis);
        long p50 = millis.get(millis.size() / 2);
        long p95 = millis.get((int) (millis.size() * 0.95));
        long max = millis.getLast();
        System.out.printf("search-load: requests=%d p50=%dms p95=%dms max=%dms%n", millis.size(), p50, p95, max);
        assertThat(p95).isLessThan(1000);
        assertThat(max).isLessThan(5000);
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private static TravelDocument travel(UUID id, String title, String description, int startsInDays, String price,
                                         List<Place> destinations, List<ActivityText> activities,
                                         List<StayText> stays, List<TransportText> transports, String manager) {
        LocalDate start = LocalDate.now().plusDays(startsInDays);
        return new TravelDocument(id, UUID.nameUUIDFromBytes(manager.getBytes(StandardCharsets.UTF_8)), manager, title,
                description, "PUBLISHED", true, start, start.plusDays(6), 7, "Europe/Athens", new BigDecimal(price),
                "EUR", 12, 10, 1L, destinations, activities, stays, transports, null);
    }

    private static TravelDocument unpublished(TravelDocument travel) {
        return new TravelDocument(travel.travelId(), travel.managerId(), travel.managerName(), travel.title(),
                travel.description(), "UNPUBLISHED", false, travel.startDate(), travel.endDate(), travel.durationDays(),
                travel.timeZone(), travel.price(), travel.currency(), travel.capacity(), travel.remainingCapacity(),
                travel.version() + 1, travel.destinations(), travel.activities(), travel.accommodations(),
                travel.transports(), null);
    }

    private static TravelDocument withPriceAndVersion(TravelDocument travel, String price, long version) {
        return new TravelDocument(travel.travelId(), travel.managerId(), travel.managerName(), travel.title(),
                travel.description(), travel.status(), travel.bookable(), travel.startDate(), travel.endDate(),
                travel.durationDays(), travel.timeZone(), new BigDecimal(price), travel.currency(), travel.capacity(),
                travel.remainingCapacity(), version, travel.destinations(), travel.activities(),
                travel.accommodations(), travel.transports(), null);
    }

    private static void put(TravelDocument travel) {
        SOURCE.put(travel.travelId(), travel);
    }

    private List<UUID> searchIds(String query) throws Exception {
        Response response = get("/api/search/travels" + (query.isEmpty() ? "" : "?" + query), Role.TRAVELER);
        assertThat(response.status()).as(query).isEqualTo(200);
        return ids(response.json());
    }

    private static List<UUID> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> UUID.fromString(item.get("travelId").asString())).toList();
    }

    private JsonNode suggestions(String text) throws Exception {
        Response response = get("/api/search/autocomplete?q=" + encode(text), Role.TRAVELER);
        assertThat(response.status()).isEqualTo(200);
        return response.json().get("items");
    }

    private static List<String> texts(JsonNode suggestions) {
        return suggestions.valueStream().map(item -> item.get("text").asString()).toList();
    }

    private static String first(JsonNode suggestions) {
        return suggestions.get(0).get("type").asString() + ":" + suggestions.get(0).get("text").asString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int notify(UUID travelId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/internal/search/index/travels/" + travelId))
                .header("X-Internal-Service-Token", "test-internal").POST(HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private Response get(String path, Role role) throws Exception {
        return send("GET", path, role);
    }

    private Response post(String path, Role role) throws Exception {
        return send("POST", path, role);
    }

    private Response send(String method, String path, Role role) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + codec.issue(UUID.randomUUID(), role, UUID.randomUUID()).value())
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Retry-After").orElse(null));
        }
    }

    private record Response(int status, String body, String retryAfter) {
        JsonNode json() {
            return TravelIndex.JSON.readTree(body);
        }
    }

    /** Serves /internal/search/travels like travel-service, from {@link #SOURCE}. */
    private static HttpServer travelServiceStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/search/travels", exchange -> {
                try (exchange) {
                    if (!"test-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))) {
                        exchange.sendResponseHeaders(403, -1);
                        return;
                    }
                    String path = exchange.getRequestURI().getPath();
                    Object body;
                    if (path.equals("/internal/search/travels")) {
                        String query = exchange.getRequestURI().getQuery();
                        int page = Integer.parseInt(query.replaceAll(".*page=(\\d+).*", "$1"));
                        int size = Integer.parseInt(query.replaceAll(".*size=(\\d+).*", "$1"));
                        List<TravelDocument> bookable = SOURCE.values().stream().filter(TravelDocument::searchable)
                                .sorted(java.util.Comparator.comparing(TravelDocument::travelId)).toList();
                        int from = Math.min(page * size, bookable.size());
                        body = Map.of("items", bookable.subList(from, Math.min(from + size, bookable.size())),
                                "page", page, "size", size, "total", bookable.size());
                    } else {
                        TravelDocument travel = SOURCE.get(UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
                        if (travel == null) {
                            exchange.sendResponseHeaders(404, -1);
                            return;
                        }
                        body = travel;
                    }
                    byte[] bytes = TravelIndex.JSON.writeValueAsBytes(body);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
