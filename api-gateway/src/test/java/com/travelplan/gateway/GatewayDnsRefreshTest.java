package com.travelplan.gateway;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import reactor.netty.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class GatewayDnsRefreshTest {
    @Autowired private ObjectProvider<HttpClientCustomizer> customizers;
    @Autowired @Qualifier("authValidationHttpClient") private HttpClient authValidationHttpClient;

    @Test void gatewayReresolvesReplacedServiceAddressesWithinFiveSeconds() {
        HttpClient client = HttpClient.create();
        for (HttpClientCustomizer customizer : customizers) client = customizer.customize(client);

        var resolver = client.configuration().getNameResolverProvider();
        assertThat(resolver).isNotNull();
        assertThat(resolver.cacheMaxTimeToLive()).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }

    @Test void tokenValidationReresolvesReplacedAuthServiceWithinFiveSeconds() {
        var resolver = authValidationHttpClient.configuration().getNameResolverProvider();
        assertThat(resolver).isNotNull();
        assertThat(resolver.cacheMaxTimeToLive()).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
