package com.travelplan.payments;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class SandboxHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SandboxHttp() {}

    static HttpResponse<String> send(HttpRequest request) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderAdapter.Unavailable("PROVIDER_TIMEOUT");
        } catch (IOException ex) {
            throw new ProviderAdapter.Unavailable("PROVIDER_UNAVAILABLE");
        }
    }

    static JsonNode json(String body) {
        try {
            return JSON.readTree(body);
        } catch (RuntimeException ex) {
            throw new ProviderAdapter.Unavailable("PROVIDER_INVALID_RESPONSE");
        }
    }

    static void requireSuccess(int status) {
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403 || status == 429 || status >= 500)
            throw new ProviderAdapter.Unavailable("PROVIDER_UNAVAILABLE");
        throw new ProviderAdapter.Rejected("PROVIDER_REJECTED_REFERENCE");
    }

    static String safeWord(String value, String fallback) {
        return value != null && value.matches("[A-Za-z0-9_ -]{1,32}") ? value : fallback;
    }

    static Duration timeout() { return Duration.ofSeconds(5); }
}
