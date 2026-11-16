package com.rootradar.apifd.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ONLY {@link Clock} bean. Everything that needs "now" injects it (never Instant.now()), so tests can pass
 * Clock.fixed(...) or a mutable test clock.
 */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
