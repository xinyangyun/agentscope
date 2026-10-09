package com.example.springbootdemo.config;

import com.example.springbootdemo.tool.FinancialNewsTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConfigurationTest {

    @TempDir
    Path workspace;

    @Test
    void harnessAgentUsesShortTermStateAndLongTermMemoryTools() {
        AgentConfiguration configuration = new AgentConfiguration();
        AgentStateStore memoryStore = configuration.shortTermMemoryStore();
        FinancialNewsTool financialNewsTool = new FinancialNewsTool(
                new ObjectMapper(), "https://example.com/api/v2/doc/doc", "24h", 8);

        try (HarnessAgent agent = configuration.harnessAgent(
                "assistant",
                "You are a helpful AI assistant.",
                "deepseek-chat",
                "test-api-key",
                "https://api.deepseek.com",
                false,
                workspace.toString(),
                memoryStore,
                financialNewsTool)) {
            assertThat(memoryStore).isInstanceOf(InMemoryAgentStateStore.class);
            assertThat(agent.getStateStore()).isSameAs(memoryStore);
            assertThat(agent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
            assertThat(agent.getToolkit().getToolNames())
                    .contains(
                            "memory_search",
                            "memory_get",
                            "memory_save",
                            "fetch_latest_financial_news");
            assertThat(agent.getSubagentAgentManager().hasAgent("financial-news")).isTrue();
            assertThat(agent.getSubagentAgentManager().getDeclaration("financial-news"))
                    .get()
                    .extracting(declaration -> declaration.getTools())
                    .isEqualTo(java.util.List.of("fetch_latest_financial_news"));

            try (HarnessAgent financialNewsAgent = (HarnessAgent) agent.getSubagentAgentManager()
                    .createAgent("financial-news", RuntimeContext.builder()
                            .userId("user-001")
                            .sessionId("session-001")
                            .build())) {
                assertThat(financialNewsAgent.getToolkit().getToolNames())
                        .contains("fetch_latest_financial_news");
                assertThat(financialNewsAgent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
            }
        }
    }
}
