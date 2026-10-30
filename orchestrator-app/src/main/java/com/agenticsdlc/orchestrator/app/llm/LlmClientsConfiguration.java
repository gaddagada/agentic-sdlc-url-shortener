package com.agenticsdlc.orchestrator.app.llm;

import java.net.http.HttpClient;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmStageAgent;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

/**
 * Registers the LLM providers and builds the {@link AgentRegistry}: the deterministic agents always, plus one
 * {@link LlmStageAgent} per role for every enabled provider. A provider that is enabled but not usable (no API key,
 * server down) is still registered and reported as unavailable by the {@link ProviderCatalog}.
 */
@Configuration(proxyBeanMethods = false)
public class LlmClientsConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(LlmClientsConfiguration.class);

    /**
     * Creates the Anthropic client (unless {@code sdlc.llm.anthropic.enabled=false}).
     *
     * @param properties LLM configuration
     * @param codec JSON codec
     * @param cacheListener receiver of prompt-cache token counts (the Micrometer metrics recorder)
     * @return the client
     */
    @Bean
    @ConditionalOnProperty(prefix = "sdlc.llm.anthropic", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    AnthropicLlmClient anthropicLlmClient(LlmProperties properties, JsonCodec codec,
                                          ObjectProvider<PromptCacheListener> cacheListener) {
        return new AnthropicLlmClient(properties.anthropic(), codec,
                cacheListener.getIfUnique(() -> PromptCacheListener.NONE));
    }

    /**
     * Creates the Ollama client (unless {@code sdlc.llm.ollama.enabled=false}) on a JDK HTTP client with a 2 s
     * connect timeout and the configured read timeout.
     *
     * @param builder auto-configured REST client builder
     * @param properties LLM configuration
     * @param codec JSON codec
     * @param clock clock
     * @return the client
     */
    @Bean
    @ConditionalOnProperty(prefix = "sdlc.llm.ollama", name = "enabled", havingValue = "true", matchIfMissing = true)
    OllamaLlmClient ollamaLlmClient(RestClient.Builder builder, LlmProperties properties, JsonCodec codec,
                                    Clock clock) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(OllamaLlmClient.PROBE_TIMEOUT).build());
        factory.setReadTimeout(properties.ollama().timeout());
        return new OllamaLlmClient(builder.clone().requestFactory(factory), properties.ollama(), codec, clock);
    }

    /**
     * Builds the agent registry.
     *
     * @param clients registered LLM providers
     * @param codec JSON codec
     * @return the registry
     */
    @Bean
    AgentRegistry agentRegistry(ObjectProvider<ProviderClient> clients, JsonCodec codec) {
        AgentRegistry.Builder builder = AgentRegistry.builder().deterministic(DeterministicAgents.all());
        for (ProviderClient client : clients.orderedStream().toList()) {
            builder.provider(client.providerId(), LlmStageAgent.forAllRoles(client, codec, client.maxOutputTokens(),
                    client.requestTimeout()));
            LOG.info("agent provider registered provider={} model={}", client.providerId(), client.model());
        }
        return builder.build();
    }

    /**
     * Creates the provider catalog.
     *
     * @param clients registered LLM providers
     * @param llm LLM configuration
     * @param properties orchestrator configuration
     * @return the catalog
     */
    @Bean
    ProviderCatalog providerCatalog(ObjectProvider<ProviderClient> clients, LlmProperties llm,
                                    OrchestratorProperties properties) {
        return new ProviderCatalog(clients.orderedStream().toList(), llm, properties.agents().defaultProvider());
    }
}
