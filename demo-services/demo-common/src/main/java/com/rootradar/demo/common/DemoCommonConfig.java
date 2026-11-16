package com.rootradar.demo.common;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Import this from each service application: {@code @Import(DemoCommonConfig.class)}. */
@Configuration
@ComponentScan(basePackageClasses = DemoCommonConfig.class)
public class DemoCommonConfig {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    Clock demoClock() {
        return Clock.systemUTC();
    }

    @Bean
    TelemetryRegistry telemetryRegistry(@Value("${spring.application.name}") String service, Clock clock) {
        return new TelemetryRegistry(service, clock);
    }

    @Bean
    FaultService faultService(@Value("${spring.application.name}") String service, Clock clock,
                              @Value("${demo.db-faults-supported:false}") boolean db) {
        return new FaultService(service, clock, db);
    }

    @Bean
    FilterRegistrationBean<TelemetryFilter> telemetryFilter(TelemetryRegistry t) {
        FilterRegistrationBean<TelemetryFilter> b = new FilterRegistrationBean<>(new TelemetryFilter(t));
        b.setOrder(Integer.MIN_VALUE + 10);
        return b;
    }
}
