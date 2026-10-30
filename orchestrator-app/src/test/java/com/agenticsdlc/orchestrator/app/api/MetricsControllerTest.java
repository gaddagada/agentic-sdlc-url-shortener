package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;

/**
 * {@link MetricsController}: the expensive authoritative listing ({@code engine.list()}) is computed at most once per
 * {@code sdlc.metrics.cache-ttl} (5 s by default), and the LLM block carries the cache and cost fields.
 */
@WebMvcTest(MetricsController.class)
@Import({MetricsControllerTest.Fixtures.class, SecuritySliceConfiguration.class})
class MetricsControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private SteppingClock clock;

    @MockitoBean
    private OrchestrationEngine engine;

    @Test
    void theResultIsReusedForTheCacheTtlAndRecomputedAfterwards() {
        Run run = Run.create("r1", GRAPH, "custom", "Build a URL shortener with analytics.", RunOptions.defaults(), T0);
        run.updateCounters(c -> c.plusLlmUsage(5_300, 340, 4_000, 1_000, 12_500L));
        when(engine.list()).thenReturn(List.of(run));

        assertThat(mvc.get().uri("/api/v1/metrics/reliability").exchange()).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.llm.cacheReadTokens").isEqualTo(4000);
        clock.advance(Duration.ofSeconds(4));
        assertThat(mvc.get().uri("/api/v1/metrics/reliability").exchange()).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.llm.estimatedCostUsd").isEqualTo(0.0125);
        verify(engine, times(1)).list();

        clock.advance(Duration.ofSeconds(2));
        assertThat(mvc.get().uri("/api/v1/metrics/reliability").exchange()).bodyJson()
                .extractingPath("$.llm.unpricedCalls").isEqualTo(0);
        verify(engine, times(2)).list();
    }

    /** A clock the test moves forward. */
    static final class SteppingClock extends Clock {

        private volatile Instant now = T0;

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * The controller's clock.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        SteppingClock clock() {
            return new SteppingClock();
        }
    }
}
