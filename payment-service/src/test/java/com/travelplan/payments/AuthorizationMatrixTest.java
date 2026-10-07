package com.travelplan.payments;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import com.travelplan.common.testing.AuthorizationMatrix;
import com.travelplan.common.testing.AuthorizationMatrix.Access;
import com.travelplan.common.testing.AuthorizationMatrix.Endpoint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.travelplan.common.testing.AuthorizationMatrix.endpoint;

/** Every payment-service endpoint, its minimum role, and proof that the rule holds when called directly. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class AuthorizationMatrixTest {
    private static final HttpServer AUTH = AuthorizationMatrix.activeSessionsAuthServer();

    static final List<Endpoint> MATRIX = List.of(
            endpoint("GET", "/api/payment-gateways", Access.ADMIN),
            endpoint("POST", "/api/payment-gateways", Access.ADMIN),
            endpoint("GET", "/api/payment-gateways/{id}", Access.ADMIN),
            endpoint("PATCH", "/api/payment-gateways/{id}", Access.ADMIN),
            endpoint("DELETE", "/api/payment-gateways/{id}", Access.ADMIN),
            endpoint("GET", "/api/payment-methods", Access.ADMIN),
            endpoint("POST", "/api/payment-methods", Access.ADMIN),
            endpoint("GET", "/api/payment-methods/{id}", Access.ADMIN),
            endpoint("PATCH", "/api/payment-methods/{id}", Access.ADMIN),
            endpoint("DELETE", "/api/payment-methods/{id}", Access.ADMIN),
            endpoint("POST", "/internal/users/{userId}/payment-cleanup", Access.INTERNAL));

    @Autowired ApplicationContext context;
    @Autowired AccessTokenCodec codec;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void authUrl(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
    }

    @AfterAll static void stopAuth() { AUTH.stop(0); }

    @Test void matrixListsEveryEndpoint() {
        AuthorizationMatrix.assertCoversAllMappings(context, MATRIX);
    }

    @Test void everyEndpointEnforcesItsRoleWhenCalledDirectly() throws Exception {
        AuthorizationMatrix.assertEnforced(port, Map.of(
                Access.TRAVELER, token(Role.TRAVELER),
                Access.TRAVEL_MANAGER, token(Role.TRAVEL_MANAGER),
                Access.ADMIN, token(Role.ADMIN)), MATRIX);
    }

    private String token(Role role) {
        return codec.issue(UUID.randomUUID(), role, UUID.randomUUID()).value();
    }
}
