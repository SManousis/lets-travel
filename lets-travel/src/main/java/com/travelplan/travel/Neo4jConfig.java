package com.travelplan.travel;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The Neo4j driver with bounded waits: a request never waits longer than the acquisition timeout for a
 * connection, so a slow or stopped database turns into a quick 503 instead of piling up requests.
 */
@Configuration
@Profile("infra")
public class Neo4jConfig {
    @Bean(destroyMethod = "close")
    Driver neo4jDriver(@Value("${neo4j.uri:bolt://neo4j:7687}") String uri,
                       @Value("${neo4j.username:neo4j}") String user,
                       @Value("${neo4j.password:change-me-neo4j}") String password,
                       @Value("${neo4j.connection-timeout:PT5S}") Duration connectionTimeout,
                       @Value("${neo4j.acquisition-timeout:PT10S}") Duration acquisitionTimeout) {
        Config config = Config.builder()
                .withConnectionTimeout(connectionTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .withConnectionAcquisitionTimeout(acquisitionTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
    }
}
