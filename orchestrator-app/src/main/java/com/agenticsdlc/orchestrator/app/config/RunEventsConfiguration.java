package com.agenticsdlc.orchestrator.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agenticsdlc.orchestrator.app.events.RunEventBus;

/**
 * Provides the in-process {@link RunEventBus}: the JPA run store publishes every committed run change to it, and the
 * console's server-sent event streams and the run observations subscribe to it. It only carries the commits of this
 * node.
 */
@Configuration(proxyBeanMethods = false)
public class RunEventsConfiguration {

    /**
     * Returns the bus (closed on shutdown).
     *
     * @return the bus
     */
    @Bean(destroyMethod = "close")
    RunEventBus runEventBus() {
        return new RunEventBus();
    }
}
