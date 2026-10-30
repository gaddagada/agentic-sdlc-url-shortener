package com.agenticsdlc.orchestrator.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the SDLC orchestrator control plane: REST API, operator console, JPA persistence, LLM providers
 * and Micrometer metrics around the {@code orchestrator-core} engine.
 *
 * <p>Beans are declared in dedicated {@code config} classes rather than here so that Spring test slices, which use
 * this class as their configuration, stay lean.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrchestratorApplication {

    /**
     * Starts the application.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
