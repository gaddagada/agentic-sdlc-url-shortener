package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link BoundedSendExecutor#forStreams}: the write executor of the event streams leaves room for the writers that
 * are returning from the streams whose slots were just freed, so a stream that takes such a slot always finds a
 * permit.
 */
class BoundedSendExecutorTest {

    @Test
    void theStreamExecutorHasAPermitForEveryStreamAndEveryWriterThatIsReturning() {
        try (BoundedSendExecutor writers = BoundedSendExecutor.forStreams(3)) {
            assertThat(writers.availablePermits()).isEqualTo(6);
        }
    }
}
