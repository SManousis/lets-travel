package com.travelplan.users;

import com.travelplan.common.security.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManagerInternalApiTest {
    private UserStore store;
    private MockMvc mvc;

    @BeforeEach void setup() {
        store = mock(UserStore.class);
        mvc = MockMvcBuilders.standaloneSetup(new UserInternalController(store, "internal-secret")).build();
    }

    @Test void returnsOnlySafeFieldsForActiveTravelManager() throws Exception {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2025-03-04T12:30:00Z");
        when(store.get(id)).thenReturn(user(id, Role.TRAVEL_MANAGER, UserDomain.Status.ACTIVE, createdAt));

        mvc.perform(get("/internal/managers/{id}", id).header("X-Internal-Service-Token", "internal-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").value(id.toString()))
                .andExpect(jsonPath("$.displayName").value("Maria Manager"))
                .andExpect(jsonPath("$.memberSince").value(createdAt.toString()))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.passportNumber").doesNotExist());
    }

    @Test void rejectsTravelerAdminDisabledManagerAndMissingIdentity() throws Exception {
        UUID traveler = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(store.get(traveler)).thenReturn(user(traveler, Role.TRAVELER, UserDomain.Status.ACTIVE, Instant.now()));
        when(store.get(admin)).thenReturn(user(admin, Role.ADMIN, UserDomain.Status.ACTIVE, Instant.now()));
        when(store.get(disabled)).thenReturn(user(disabled, Role.TRAVEL_MANAGER, UserDomain.Status.DISABLED, Instant.now()));
        when(store.get(missing)).thenThrow(new UserStore.NotFound());

        assertStatus(traveler, 409);
        assertStatus(admin, 409);
        assertStatus(disabled, 409);
        assertStatus(missing, 404);
    }

    @Test void requiresCorrectInternalToken() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/internal/managers/{id}", id)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/managers/{id}", id).header("X-Internal-Service-Token", "wrong"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(store);
    }

    private void assertStatus(UUID id, int expected) throws Exception {
        mvc.perform(get("/internal/managers/{id}", id).header("X-Internal-Service-Token", "internal-secret"))
                .andExpect(status().is(expected));
    }

    private static UserDomain.User user(UUID id, Role role, UserDomain.Status status, Instant createdAt) {
        return new UserDomain.User(id, "private@example.com", "Maria", "Manager", role, status, createdAt);
    }
}
