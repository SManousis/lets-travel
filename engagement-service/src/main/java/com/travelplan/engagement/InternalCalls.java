package com.travelplan.engagement;

import com.travelplan.common.RequestIds;
import com.travelplan.common.CircuitBreakers;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;

/** Shared helpers for the bounded internal service clients. */
final class InternalCalls {
    private InternalCalls() {}

    static String requestId() {
        return RequestIds.current();
    }

    /** Sends a request; an unreachable dependency becomes DependencyUnavailable (503). */
    static HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ManagerPageService.DependencyUnavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ManagerPageService.DependencyUnavailable();
        }
    }

    static String required(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank())
            throw new ManagerPageService.DependencyUnavailable();
        return value.asString();
    }
}
