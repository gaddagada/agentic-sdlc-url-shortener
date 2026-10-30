package com.agenticsdlc.orchestrator.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.llm.JsonSchemas;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmRequest;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmResponse;
import com.agenticsdlc.orchestrator.core.agent.llm.PromptTemplates;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

/**
 * Live call to the Anthropic API (opt-in: runs only when {@code ANTHROPIC_API_KEY} is set; excluded from CI by
 * that condition). Asks for a small {@link RequirementSpec} through the real SDK and structured outputs.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicLlmClientLiveTest {

    @Test
    void returnsAValidRequirementSpec() throws Exception {
        JsonCodec codec = JsonCodec.standard();
        LlmProperties.Anthropic settings = new LlmProperties.Anthropic(true, "claude-opus-5-5",
                System.getenv("ANTHROPIC_API_KEY"), 16000, Duration.ofSeconds(180));
        String user = "Requirement: Add a GET /api/v1/links/{code}/qr endpoint to the URL shortener that returns a "
                + "PNG QR code of the short URL. Keep the output small: at most 3 functional requirements, 1 "
                + "non-functional requirement and 3 acceptance criteria.\n\nJSON schema of the expected output:\n"
                + codec.toJson(JsonSchemas.forRecord(RequirementSpec.class));
        try (AnthropicLlmClient client = new AnthropicLlmClient(settings, codec)) {
            LlmResponse<RequirementSpec> response = client.complete(
                    new LlmRequest(PromptTemplates.system(AgentRole.REQUIREMENTS), user, 16000,
                            Duration.ofSeconds(180)), RequirementSpec.class);

            RequirementSpec spec = response.output();
            spec.validate();
            assertThat(spec.functional()).isNotEmpty();
            assertThat(response.usage().inputTokens()).isPositive();
            assertThat(response.usage().outputTokens()).isPositive();
            assertThat(response.model()).startsWith("claude-opus-5-5");
        }
    }
}
