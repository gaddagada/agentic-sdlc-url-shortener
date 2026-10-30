package com.agenticsdlc.orchestrator.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmException;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmRequest;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmResponse;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

import tools.jackson.core.JacksonException;

/**
 * {@link OllamaLlmClient} against a mocked Ollama HTTP API ({@code MockRestServiceServer}).
 */
@RestClientTest
class OllamaLlmClientTest {

    private static final String BASE = "http://ollama.test:11434";
    private static final JsonCodec CODEC = JsonCodec.standard();
    private static final LlmRequest REQUEST = new LlmRequest("system prompt", "user prompt", 4000,
            Duration.ofSeconds(60));
    private static final DocumentationBundle BUNDLE = new DocumentationBundle("## Readme", "## API", "## Runbook",
            "Added bulk links", List.of("ADR-1: use 302"));

    @Autowired
    private RestClient.Builder builder;

    @Autowired
    private MockServerRestClientCustomizer customizer;

    private MockRestServiceServer server;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T10:00:00Z"));
    private OllamaLlmClient client;

    @BeforeEach
    void setUp() {
        LlmProperties.Ollama settings = new LlmProperties.Ollama(true, BASE + "/", "qwen2.5-coder:7b",
                Duration.ofSeconds(240), 16384, 8192);
        // the builder is a prototype bean: every test instance gets its own, bound to its own mock server
        server = customizer.getServer(builder);
        client = new OllamaLlmClient(builder, settings, CODEC, clock);
    }

    @Test
    void mapsASuccessfulChatResponseToTheTypedRecord() throws Exception {
        server.expect(once(), requestTo(BASE + "/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("qwen2.5-coder:7b"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("system prompt"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.format.type").value("object"))
                .andExpect(jsonPath("$.format.properties.adrs.type").value("array"))
                .andExpect(jsonPath("$.options.temperature").value(0))
                .andExpect(jsonPath("$.options.num_ctx").value(16384))
                .andExpect(jsonPath("$.options.num_predict").value(4000))
                .andRespond(withSuccess(chatResponse(CODEC.toJson(BUNDLE), "stop"), MediaType.APPLICATION_JSON));

        LlmResponse<DocumentationBundle> response = client.complete(REQUEST, DocumentationBundle.class);

        assertThat(response.output()).isEqualTo(BUNDLE);
        assertThat(response.usage().inputTokens()).isEqualTo(812L);
        assertThat(response.usage().outputTokens()).isEqualTo(233L);
        assertThat(response.model()).isEqualTo("qwen2.5-coder:7b");
        assertThat(response.rawText()).isEqualTo(CODEC.toJson(BUNDLE));
        server.verify();
    }

    @Test
    void theStableContextIsSentBeforeTheUserPromptInTheSingleUserMessage() throws Exception {
        LlmRequest request = new LlmRequest("system prompt", "REQUIREMENT: shorten links", "user prompt", 4000,
                Duration.ofSeconds(60));
        server.expect(once(), requestTo(BASE + "/api/chat"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].content").value("system prompt"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("REQUIREMENT: shorten links\nuser prompt"))
                .andRespond(withSuccess(chatResponse(CODEC.toJson(BUNDLE), "stop"), MediaType.APPLICATION_JSON));

        assertThat(client.complete(request, DocumentationBundle.class).output()).isEqualTo(BUNDLE);
        server.verify();
    }

    @Test
    void serverErrorsAreRetryable() {
        server.expect(requestTo(BASE + "/api/chat")).andRespond(withServerError().body("model crashed"));

        assertThatThrownBy(() -> client.complete(REQUEST, DocumentationBundle.class))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).contains("HTTP 500").contains("model crashed");
                });
    }

    @Test
    void clientErrorsAreNotRetryable() {
        server.expect(requestTo(BASE + "/api/chat")).andRespond(withBadRequest().body("model not found"));

        assertThatThrownBy(() -> client.complete(REQUEST, DocumentationBundle.class))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isFalse());
    }

    @Test
    void connectionErrorsAreRetryable() {
        server.expect(requestTo(BASE + "/api/chat")).andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> client.complete(REQUEST, DocumentationBundle.class))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).contains("unreachable");
                });
    }

    @Test
    void truncatedOutputIsRetryable() {
        server.expect(requestTo(BASE + "/api/chat"))
                .andRespond(withSuccess(chatResponse("{\"readmeSection\": \"", "length"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete(REQUEST, DocumentationBundle.class))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void unparseableContentSurfacesAsAJacksonException() {
        server.expect(requestTo(BASE + "/api/chat"))
                .andRespond(withSuccess(chatResponse("this is not JSON", "stop"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete(REQUEST, DocumentationBundle.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void availabilityIsProbedOnceAndCachedFor30Seconds() {
        server.expect(once(), requestTo(BASE + "/api/tags")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"models\": []}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/api/tags")).andRespond(withServerError());

        assertThat(client.isAvailable()).isTrue();
        assertThat(client.detail()).contains("reachable");
        clock.advance(Duration.ofSeconds(29));
        assertThat(client.isAvailable()).isTrue();

        clock.advance(Duration.ofSeconds(2));
        assertThat(client.isAvailable()).isFalse();
        assertThat(client.detail()).contains("not reachable");
        server.verify();
    }

    private static String chatResponse(String content, String doneReason) {
        return CODEC.toJson(Map.of("model", "qwen2.5-coder:7b", "created_at", "2026-10-01T10:00:00Z",
                "message", Map.of("role", "assistant", "content", content), "done", true, "done_reason", doneReason,
                "prompt_eval_count", 812, "eval_count", 233));
    }

    /**
     * A clock tests can move forward.
     */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
}
