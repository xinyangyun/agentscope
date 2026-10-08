package com.example.springbootdemo.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekModelProvider;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
public class AgentConfiguration {

    @Bean
    public AgentStateStore shortTermMemoryStore() {
        return new InMemoryAgentStateStore();
    }

    @Bean
    public HarnessAgent harnessAgent(
            @Value("${agentscope.agent.name:assistant}") String name,
            @Value("${agentscope.agent.system-prompt:You are a helpful AI assistant.}") String systemPrompt,
            @Value("${agentscope.agent.model:deepseek-flash}") String model,
            @Value("${agentscope.agent.api-key:}") String apiKey,
            @Value("${agentscope.agent.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agentscope.agent.workspace:.agentscope/workspace}") String workspace,
            AgentStateStore shortTermMemoryStore) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Missing DeepSeek API key. Set DEEPSEEK_API_KEY before starting the application.");
        }

        Model chatModel = new DeepSeekModelProvider().create(
                "deepseek:" + model,
                ModelCreationContext.builder()
                .apiKey(apiKey)
                .baseUrl(normalizeBaseUrl(baseUrl))
                .stream(true)
                .build());

        return HarnessAgent.builder()
                .name(name)
                .sysPrompt(systemPrompt)
                .model(chatModel)
                .stateStore(shortTermMemoryStore)
                .workspace(Paths.get(workspace))
                .build();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        String endpoint = "/chat/completions";
        if (normalized.endsWith(endpoint)) {
            normalized = normalized.substring(0, normalized.length() - endpoint.length());
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
