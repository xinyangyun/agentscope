package com.example.springbootdemo.config;

import com.example.springbootdemo.tool.FinancialNewsTool;
import com.example.springbootdemo.tool.MarketIndexTool;
import com.example.springbootdemo.tool.PushPlusTool;
import com.example.springbootdemo.tool.StockQuoteTool;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekModelProvider;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.nio.file.Paths;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;

@Configuration
@PropertySource(value = "classpath:application-plan-mode.yml", factory = YamlPropertySourceFactory.class)
public class PlanModeAgentConfiguration {

    @Bean
    public PlanModeAgentClient planModeAgentClient(
            @Value("${agentscope.plan-agent.name:plan-assistant}") String name,
            @Value("${agentscope.plan-agent.system-prompt:You are a plan-first AI assistant. Investigate, write a concrete plan, request approval, and only then execute it.}")
            String systemPrompt,
            @Value("${agentscope.plan-agent.model:deepseek-flash}") String model,
            @Value("${agentscope.plan-agent.api-key:}") String apiKey,
            @Value("${agentscope.plan-agent.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agentscope.plan-agent.enable-thinking:false}") boolean enableThinking,
            @Value("${agentscope.plan-agent.stream:false}") boolean stream,
            @Value("${agentscope.plan-agent.model-timeout-seconds:120}") int modelTimeoutSeconds,
            @Value("${agentscope.plan-agent.model-max-attempts:3}") int modelMaxAttempts,
            @Value("${agentscope.plan-agent.skills-dir:skills}") String skillsDir,
            @Value("${agentscope.plan-agent.workspace:.agentscope/plan-workspace}") String workspace,
            @Value("${agentscope.plan-agent.plan-file-directory:plans}") String planFileDirectory,
            @Value("${agentscope.plan-agent.allow-shell-in-plan-mode:false}") boolean allowShellInPlanMode,
            FinancialNewsTool financialNewsTool,
            PushPlusTool pushPlusTool,
            StockQuoteTool stockQuoteTool,
            MarketIndexTool marketIndexTool) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Missing Plan Mode API key. Set AGENTSCOPE_V2_API_KEY or DEEPSEEK_API_KEY before starting the application.");
        }

        Model chatModel = new DeepSeekModelProvider().create(
                "deepseek:" + model,
                ModelCreationContext.builder()
                        .apiKey(apiKey)
                        .baseUrl(normalizeBaseUrl(baseUrl))
                        .stream(stream)
                        .enableThinking(enableThinking)
                        .component(GenerateOptions.class,
                                modelOptions(stream, modelTimeoutSeconds, modelMaxAttempts))
                        .build());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(financialNewsTool);
        toolkit.registerTool(pushPlusTool);
        toolkit.registerTool(stockQuoteTool);
        toolkit.registerTool(marketIndexTool);

        HarnessAgent agent = HarnessAgent.builder()
                .name(name)
                .sysPrompt(systemPrompt)
                .model(chatModel)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .enablePendingToolRecovery(true)
                .enableTaskList()
                .enablePlanMode()
                .planFileDirectory(planFileDirectory)
                .allowShellInPlanMode(allowShellInPlanMode)
                .projectGlobalSkillsDir(Paths.get(skillsDir))
                .workspace(Paths.get(workspace))
                .build();
        return new PlanModeAgentClient(agent);
    }

    GenerateOptions modelOptions(boolean stream, int timeoutSeconds, int maxAttempts) {
        if (timeoutSeconds < 1 || maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("Model timeout must be positive and max attempts must be between 1 and 5");
        }
        return GenerateOptions.builder()
                .stream(stream)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        // Streaming retries could append a second response to a partial first response.
                        .maxAttempts(stream ? 1 : maxAttempts)
                        .initialBackoff(Duration.ofMillis(500))
                        .maxBackoff(Duration.ofSeconds(2))
                        .retryOn(this::isTransportFailure)
                        .build())
                .build();
    }

    private boolean isTransportFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if ((cause instanceof IOException && !(cause instanceof JsonProcessingException))
                    || cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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

    public static final class PlanModeAgentClient implements AutoCloseable {

        private final HarnessAgent agent;

        public PlanModeAgentClient(HarnessAgent agent) {
            this.agent = agent;
        }

        public HarnessAgent agent() {
            return agent;
        }

        @Override
        public void close() {
            agent.close();
        }
    }
}
