package com.travelplan.travel.recommend;

import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class RecommendationConfig {

    /** The graph exists only where Neo4j does (the infra profile); see RecommendationService. */
    @Configuration
    @Profile("infra")
    static class Graph {
        @Bean
        RecommendationGraph recommendationGraph(Driver driver) {
            return new RecommendationGraph(driver);
        }
    }
}
