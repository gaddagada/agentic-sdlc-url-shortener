package com.agenticsdlc.orchestrator.app.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the UTC {@link Clock} shared by the engine, metrics and views (tests may replace it).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Returns the system UTC clock.
     *
     * @return the clock
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
