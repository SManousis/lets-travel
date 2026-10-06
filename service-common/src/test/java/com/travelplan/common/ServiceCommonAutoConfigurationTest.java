package com.travelplan.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCommonAutoConfigurationTest {
    private final AutoConfigurations config = AutoConfigurations.of(ServiceCommonAutoConfiguration.class);

    @Test void registersTraceFilterForServletServices() {
        new WebApplicationContextRunner().withConfiguration(config)
                .withPropertyValues("spring.application.name=search-service", "SERVICE_INSTANCE_ID=pod-7")
                .run(context -> assertThat(context.getBean(RequestTraceFilter.class).instanceId()).isEqualTo("pod-7"));
    }

    @Test void staysOutOfNonWebApplications() {
        new ApplicationContextRunner().withConfiguration(config)
                .withPropertyValues("spring.application.name=batch")
                .run(context -> assertThat(context).doesNotHaveBean(RequestTraceFilter.class));
    }
}
