package com.travelplan.search;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling drives the index sync (change queue every second, rebuild check every 30 seconds). */
@Configuration
@EnableScheduling
class SearchConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
