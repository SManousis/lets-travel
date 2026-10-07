package com.travelplan.engagement;

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

/** Every engagement-service endpoint, its minimum role, and proof that the rule holds when called directly. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class AuthorizationMatrixTest {
    private static final HttpServer AUTH = AuthorizationMatrix.activeSessionsAuthServer();

    static final List<Endpoint> MATRIX = List.of(
            endpoint("GET", "/api/managers/{managerId}", Access.TRAVELER),
            endpoint("POST", "/api/subscriptions", Access.TRAVELER),
            endpoint("GET", "/api/subscriptions", Access.TRAVELER),
            endpoint("GET", "/api/subscriptions/{subscriptionId}", Access.TRAVELER),
            endpoint("POST", "/api/subscriptions/{subscriptionId}/cancellation", Access.TRAVELER),
            endpoint("GET", "/api/managed-travels/{travelId}/subscribers", Access.TRAVEL_MANAGER),
            endpoint("POST", "/api/managed-travels/{travelId}/subscribers/{subscriptionId}/removal", Access.TRAVEL_MANAGER),
            endpoint("POST", "/api/feedback", Access.TRAVELER),
            endpoint("GET", "/api/feedback", Access.TRAVEL_MANAGER),
            endpoint("GET", "/api/feedback/mine", Access.TRAVELER),
            endpoint("POST", "/api/reports", Access.TRAVELER),
            endpoint("GET", "/api/reports", Access.TRAVELER),
            endpoint("GET", "/api/admin/reports", Access.ADMIN),
            endpoint("GET", "/api/admin/reports/{reportId}", Access.ADMIN),
            endpoint("PATCH", "/api/admin/reports/{reportId}", Access.ADMIN),
            endpoint("GET", "/internal/subscriptions/{subscriptionId}", Access.INTERNAL),
            endpoint("POST", "/internal/subscriptions/{subscriptionId}/payment-outcome", Access.INTERNAL)
    );

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
