package com.travelplan.payments;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "payments.simulation.enabled=true",
        "payments.stripe.secret-key=sk_test_sim_testonly",
        "payments.paypal.client-id=sim_client_testonly",
        "payments.paypal.client-secret=sim_secret_testonly"
})
class LocalProviderSimulationTest {
    private static final String STRIPE_REFERENCE = "pm_simulatedvisa001";
    private static final String PAYPAL_REFERENCE = "VAULT_SIM_001";
    private static final AtomicBoolean stripeDetached = new AtomicBoolean();
    private static final AtomicBoolean paypalDeleted = new AtomicBoolean();
    private static final HttpServer simulator = startSimulator();

    @Autowired private StripeAdapter stripe;
    @Autowired private PayPalAdapter paypal;

    @DynamicPropertySource
    static void localProvider(DynamicPropertyRegistry registry) {
        registry.add("payments.simulation.base-url", () -> "http://127.0.0.1:" + simulator.getAddress().getPort());
    }

    @AfterAll static void stopSimulator() { simulator.stop(0); }

    @Test void stripeReferenceCanBeInspectedAndDetachedWithSyntheticCredential() {
        var connected = stripe.connect(STRIPE_REFERENCE, UUID.randomUUID().toString());
        assertThat(connected.providerReference()).isEqualTo(STRIPE_REFERENCE);
        assertThat(connected.displayMetadata()).isEqualTo("visa •••• 4242");

        stripe.detach(STRIPE_REFERENCE, UUID.randomUUID().toString());
        assertThat(stripeDetached).isTrue();
    }

    @Test void paypalReferenceCanBeInspectedAndDeletedWithSyntheticCredential() {
        var connected = paypal.connect(PAYPAL_REFERENCE, UUID.randomUUID().toString());
        assertThat(connected.providerReference()).isEqualTo(PAYPAL_REFERENCE);
        assertThat(connected.displayMetadata()).isEqualTo("VISA •••• 1111");

        paypal.detach(PAYPAL_REFERENCE, UUID.randomUUID().toString());
        assertThat(paypalDeleted).isTrue();
    }

    @Test void syntheticPaypalCredentialsAreRejectedOutsideSimulation() {
        assertThat(PayPalAdapter.credentialsAllowed("sim_client_testonly", "sim_secret_testonly", false)).isFalse();
        assertThat(PayPalAdapter.credentialsAllowed("sim_client_testonly", "sim_secret_testonly", true)).isTrue();
    }

    private static HttpServer startSimulator() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/payment_methods/" + STRIPE_REFERENCE, exchange -> {
                try (exchange) {
                    if (!"Bearer sk_test_sim_testonly".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        respond(exchange, 401, "{}");
                    } else if ("POST".equals(exchange.getRequestMethod())
                            && exchange.getRequestURI().getPath().endsWith("/detach")) {
                        stripeDetached.set(true);
                        respond(exchange, 200, "{\"id\":\"" + STRIPE_REFERENCE + "\"}");
                    } else {
                        respond(exchange, 200, "{\"id\":\"" + STRIPE_REFERENCE
                                + "\",\"livemode\":false,\"customer\":\"cus_simulated001\","
                                + "\"type\":\"card\",\"card\":{\"brand\":\"visa\",\"last4\":\"4242\"}}");
                    }
                }
            });
            server.createContext("/v1/oauth2/token", exchange -> {
                try (exchange) {
                    String expected = "Basic " + Base64.getEncoder().encodeToString(
                            "sim_client_testonly:sim_secret_testonly".getBytes(StandardCharsets.UTF_8));
                    respond(exchange, expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            ? 200 : 401, "{\"access_token\":\"sim_access_token\"}");
                }
            });
            server.createContext("/v3/vault/payment-tokens/" + PAYPAL_REFERENCE, exchange -> {
                try (exchange) {
                    if (!"Bearer sim_access_token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        respond(exchange, 401, "{}");
                    } else if ("DELETE".equals(exchange.getRequestMethod())) {
                        paypalDeleted.set(true);
                        respond(exchange, 204, "");
                    } else {
                        respond(exchange, 200, "{\"id\":\"" + PAYPAL_REFERENCE
                                + "\",\"customer\":{\"id\":\"PAYPAL_SIM_CUSTOMER_001\"},"
                                + "\"payment_source\":{\"card\":{\"brand\":\"VISA\",\"last_digits\":\"1111\"}}}");
                    }
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start local provider simulator", exception);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
