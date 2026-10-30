package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The {@link PersistenceScenarioContract} on PostgreSQL ({@code postgres:17-alpine} via Testcontainers): the Flyway
 * migrations V1 to V3 run on PostgreSQL (the {@code postgres} profile, with the container's connection details),
 * Hibernate validates the schema, the deterministic greenfield run completes, its audit chain verifies after the
 * round trip through PostgreSQL, the run row carries the listing and progress columns, the paged runs list query
 * returns it, the JDBC run lease was created and released, and the release change record signature verifies.
 *
 * <p>Run by failsafe in {@code verify}; skipped (not failed) where Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {PersistenceScenarioContract.SIGNING_KEY, PersistenceScenarioContract.SIGNING_KEY_ID,
    "sdlc.engine.lease-provider=jdbc"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres"})
@Import(RecordingLeases.Registration.class)
class PostgresPersistenceIT extends PersistenceScenarioContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private DataSource dataSource;

    @Test
    void theApplicationRunsOnPostgresql() {
        String product = JdbcClient.create(dataSource).sql("SELECT version()").query(String.class).single();

        assertThat(product).startsWith("PostgreSQL 17");
        assertThat(JdbcClient.create(dataSource).sql("SELECT COUNT(*) FROM run_leases").query(Long.class).single())
                .isNotNull();
    }
}
