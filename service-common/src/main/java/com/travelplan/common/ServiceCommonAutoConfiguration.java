package com.travelplan.common;

import com.travelplan.common.audit.AuditLog;
import com.travelplan.common.audit.JdbcAuditSink;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Bean
    AuditLog auditLog(@Value("${spring.application.name}") String serviceName, ObjectProvider<AuditLog.AuditSink> sinks) {
        return new AuditLog(serviceName, sinks.orderedStream().toList(), Clock.systemUTC());
    }

    /** Services with a relational database keep their audit records in its audit_event table. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    static class JdbcAudit {
        @Bean
        JdbcAuditSink jdbcAuditSink(ObjectProvider<JdbcTemplate> jdbc) {
            return new JdbcAuditSink(jdbc);
        }
    }
}
