package com.travelplan.payments;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentDefaultSelectionTest {
    private PaymentStore store;
    private MockMvc mvc;
    private UUID userId;
    private UUID gatewayId;

    @BeforeEach void setup() {
        store = new PaymentStore();
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(store, List.of(),
                new PaymentDeletionService(store, List.of()),
                new PaymentUserClient("http://127.0.0.1:1", "test"))).build();
        userId = UUID.randomUUID();
        gatewayId = store.createGateway("STRIPE", "Sandbox").id();
    }

    @Test void firstActiveMethodBecomesDefaultAndAnotherCanBeSelected() throws Exception {
        UUID first = activeMethod("pm_one");
        UUID second = activeMethod("pm_two");

        mvc.perform(get("/api/payment-methods/{id}", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get("/api/payment-methods/{id}", second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(false));

        mvc.perform(patch("/api/payment-methods/{id}", second)
                        .contentType("application/json").content("{\"isDefault\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get("/api/payment-methods/{id}", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    @Test void defaultCannotBeClearedWhileActiveAndInactiveCannotBeSelected() throws Exception {
        UUID first = activeMethod("pm_one");
        UUID pending = store.beginConnect(userId, gatewayId, "pm_pending", "Pending",
                UUID.randomUUID().toString(), "hash-pending").method().id();

        mvc.perform(patch("/api/payment-methods/{id}", first)
                        .contentType("application/json").content("{\"isDefault\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFAULT_METHOD_REQUIRED"));
        mvc.perform(patch("/api/payment-methods/{id}", pending)
                        .contentType("application/json").content("{\"isDefault\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("METHOD_NOT_ACTIVE"));
    }

    @Test void deletingDefaultPromotesAnotherActiveMethod() throws Exception {
        UUID first = activeMethod("pm_one");
        UUID second = activeMethod("pm_two");
        var deletion = store.beginDelete(first);

        assertThat(deletion.method().status()).isEqualTo("DELETION_PENDING");
        mvc.perform(get("/api/payment-methods/{id}", second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        store.failDelete(deletion.method(), "PROVIDER_UNAVAILABLE");
        mvc.perform(get("/api/payment-methods/{id}", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    @Test void createTimeSelectionSurvivesPendingRetryAndDoesNotAffectAnotherUser() throws Exception {
        UUID first = activeMethod("pm_one");
        UUID otherUser = UUID.randomUUID();
        var otherClaim = store.beginConnect(otherUser, gatewayId, "pm_other_user", "Other",
                UUID.randomUUID().toString(), "hash-other").method();
        store.completeConnect(otherClaim, "visa •••• 1111");

        String key = UUID.randomUUID().toString();
        var claim = store.beginConnect(userId, gatewayId, "pm_selected", "Selected", key,
                "hash-selected", true).method();
        assertThat(store.beginConnect(userId, gatewayId, "pm_selected", "Selected", key,
                "hash-selected", true).shouldInspect()).isFalse();
        store.failConnect(claim, "PROVIDER_UNAVAILABLE");
        var retry = store.beginConnect(userId, gatewayId, "pm_selected", "Selected", key,
                "hash-selected", true);
        assertThat(retry.shouldInspect()).isTrue();
        UUID selected = store.completeConnect(retry.method(), "visa •••• 2222").id();

        mvc.perform(get("/api/payment-methods/{id}", first)).andExpect(jsonPath("$.isDefault").value(false));
        mvc.perform(get("/api/payment-methods/{id}", selected)).andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get("/api/payment-methods/{id}", otherClaim.id()))
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test void methodPatchRejectsProviderFieldsWithoutChangingLocalLabel() throws Exception {
        UUID id = activeMethod("pm_one");

        mvc.perform(patch("/api/payment-methods/{id}", id)
                        .contentType("application/json")
                        .content("{\"displayLabel\":\"Changed\",\"providerReference\":\"pm_other\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/payment-methods/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayLabel").value("Label pm_one"));
    }

    private UUID activeMethod(String reference) {
        var claim = store.beginConnect(userId, gatewayId, reference, "Label " + reference,
                UUID.randomUUID().toString(), "hash-" + reference).method();
        return store.completeConnect(claim, "visa •••• 4242").id();
    }
}
