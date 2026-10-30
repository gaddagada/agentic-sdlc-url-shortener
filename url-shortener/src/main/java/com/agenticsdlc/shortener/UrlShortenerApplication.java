package com.agenticsdlc.shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the URL shortener service.
 *
 * <p>Cross-cutting features (async, caching, scheduling) are enabled in dedicated {@code config} classes rather than
 * here, so that Spring test slices, which use this class as their configuration, stay lean.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UrlShortenerApplication {

    /**
     * Starts the application.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
