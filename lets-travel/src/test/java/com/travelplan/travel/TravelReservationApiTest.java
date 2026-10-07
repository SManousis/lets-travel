package com.travelplan.travel;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.testing.AuthorizationMatrix;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class TravelReservationApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer AUTH = AuthorizationMatrix.activeSessionsAuthServer();
    @Autowired TravelStore store;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void authUrl(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
    }

    @AfterAll static void stopAuth() { AUTH.stop(0); }

    @Test void reservesReportsAndReleasesPlacesWithTheServiceToken() throws Exception {
        var destination = store.createDestination("Chania " + UUID.randomUUID(), "GR");
        var created = store.create(UUID.randomUUID(), "Crete", "Island week", LocalDate.now().plusDays(30),
                LocalDate.now().plusDays(36), 1, new BigDecimal("450.00"), "EUR",
                List.of(new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null)),
                List.of(), List.of(), List.of(), "Europe/Athens");
        store.publish(created.id());
        UUID traveler = UUID.randomUUID();
        String base = "/internal/travels/" + created.id();

        HttpResponse<String> reserved = send("POST", base + "/reservations", "{\"travelerId\":\"" + traveler + "\"}", "test-internal");
        assertThat(reserved.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(reserved.body());
        assertThat(body.get("managerId").asString()).isEqualTo(created.managerId().toString());
        assertThat(body.get("timeZone").asString()).isEqualTo("Europe/Athens");
        assertThat(body.get("price").decimalValue()).isEqualByComparingTo("450.00");
        assertThat(body.get("remainingCapacity").asInt()).isZero();
        assertThat(send("POST", base + "/reservations", "{\"travelerId\":\"" + traveler + "\"}", "test-internal").statusCode())
                .isEqualTo(200);

        HttpResponse<String> full = send("POST", base + "/reservations", "{\"travelerId\":\"" + UUID.randomUUID() + "\"}", "test-internal");
        assertThat(full.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(full.body()).get("code").asString()).isEqualTo("TRAVEL_FULL");
        assertThat(send("POST", base + "/reservations", "{}", "test-internal").statusCode()).isEqualTo(400);
        assertThat(send("POST", base + "/reservations", "{\"travelerId\":\"" + traveler + "\"}", "wrong").statusCode())
                .isEqualTo(403);

        assertThat(send("DELETE", base + "/reservations/" + traveler, null, "test-internal").statusCode()).isEqualTo(204);
        assertThat(send("DELETE", base + "/reservations/" + traveler, null, "test-internal").statusCode()).isEqualTo(204);
        HttpResponse<String> summary = send("GET", base, null, "test-internal");
        assertThat(JSON.readTree(summary.body()).get("remainingCapacity").asInt()).isEqualTo(1);
        assertThat(send("GET", "/internal/travels/" + UUID.randomUUID(), null, "test-internal").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("X-Internal-Service-Token", token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
