package com.agenticsdlc.orchestrator.app;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The {@link PersistenceScenarioContract} on the in-memory H2 database of the {@code test} profile, with a signing
 * key and the default JDBC lease provider. {@code PostgresPersistenceIT} runs the same contract on PostgreSQL.
 */
@SpringBootTest(properties = {PersistenceScenarioContract.SIGNING_KEY, PersistenceScenarioContract.SIGNING_KEY_ID,
    "sdlc.engine.lease-provider=jdbc"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RecordingLeases.Registration.class)
class H2PersistenceScenarioTest extends PersistenceScenarioContract {
}
