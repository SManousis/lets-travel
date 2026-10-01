package com.travelplan.payments;

import java.net.URI;

final class ProviderApiEndpoint {
    private ProviderApiEndpoint() {}

    static String baseUrl(boolean simulated, String simulatorBaseUrl, String realBaseUrl) {
        if (!simulated) return realBaseUrl;
        if (simulatorBaseUrl == null || simulatorBaseUrl.isBlank())
            throw new IllegalArgumentException("Payment simulation requires a simulator base URL");
        URI uri = URI.create(simulatorBaseUrl);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Payment simulator URL must be an HTTP host and port");
        return simulatorBaseUrl;
    }
}
