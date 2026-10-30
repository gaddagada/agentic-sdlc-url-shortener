package com.agenticsdlc.orchestrator.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduled tasks: the periodic engine maintenance ({@code EngineMaintenance}: recovery of runs whose node
 * died once their lease expired, eviction of idle runs from memory) every {@code sdlc.engine.maintenance-interval}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
