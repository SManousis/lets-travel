package com.travelplan.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServiceCommonAutoConfiguration {

    @Bean
    RequestTraceFilter requestTraceFilter(@Value("${spring.application.name}") String serviceName,
            @Value("${SERVICE_INSTANCE_ID:}") String instanceId) {
        return new RequestTraceFilter(serviceName, instanceId);
    }
}
