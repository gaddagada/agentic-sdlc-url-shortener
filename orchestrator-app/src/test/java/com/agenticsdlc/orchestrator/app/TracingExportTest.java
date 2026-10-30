package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.agenticsdlc.orchestrator.app.events.RunEventBus;
import com.agenticsdlc.orchestrator.app.metrics.RunObservations;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;

/**
 * OpenTelemetry tracing is on the classpath but exports nothing by default: without
 * {@code management.opentelemetry.tracing.export.otlp.endpoint} no OTLP exporter exists, so a default run has no
 * network side effects; with the endpoint the OTLP/HTTP span exporter is configured. Observations are bridged to the
 * tracer in both cases, and the run observations subscribe to the run event bus.
 */
class TracingExportTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class WithoutAnEndpoint {

        @Autowired
        private ApplicationContext context;

        @Test
        void observationsBecomeTimersWithoutLongTaskTimersThatWouldCollideWithExistingMeters() {
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            Observation.createNotStarted(RunObservations.RUN, context.getBean(ObservationRegistry.class))
                    .lowCardinalityKeyValue("scenario", "tracing-test").start().stop();

            assertThat(meters.find(RunObservations.RUN).tag("scenario", "tracing-test").timer()).isNotNull();
            assertThat(meters.find(RunObservations.RUN + ".active").longTaskTimer()).isNull();
        }

        @Test
        void nothingIsExportedButObservationsAreTraced() {
            assertThat(context.getBeansOfType(OtlpHttpSpanExporter.class)).isEmpty();
            assertThat(context.getBean(Tracer.class)).isNotEqualTo(Tracer.NOOP);
            assertThat(context.getBean(ObservationRegistry.class).isNoop()).isFalse();
            assertThat(context.getBean(RunObservations.class)).isNotNull();
            assertThat(context.getBean(RunEventBus.class).subscriberCount())
                    .as("run observations and the event stream hub").isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @SpringBootTest(properties = "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces")
    @ActiveProfiles("test")
    class WithAnEndpoint {

        @Autowired
        private ApplicationContext context;

        @Test
        void theOtlpSpanExporterIsConfigured() {
            assertThat(context.getBeansOfType(OtlpHttpSpanExporter.class)).hasSize(1);
        }
    }
}
