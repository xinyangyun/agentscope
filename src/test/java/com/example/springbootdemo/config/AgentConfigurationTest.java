package com.example.springbootdemo.config;

import com.example.springbootdemo.tool.FinancialNewsTool;
import com.example.springbootdemo.tool.PushPlusTool;
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
        PushPlusTool pushPlusTool = new PushPlusTool(
                new ObjectMapper(), "test-token", "https://example.com/push");

        try (HarnessAgent agent = configuration.harnessAgent(
                "assistant",
                "You are a helpful AI assistant.",
                "deepseek-chat",
                "test-api-key",
                "https://api.deepseek.com",
                false,
                Path.of("skills").toString(),
                workspace.toString(),
                memoryStore,
                financialNewsTool,
                pushPlusTool)) {
            assertThat(memoryStore).isInstanceOf(InMemoryAgentStateStore.class);
            assertThat(agent.getStateStore()).isSameAs(memoryStore);
            assertThat(agent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
            assertThat(agent.getToolkit().getToolNames())
                    .contains(
                            "memory_search",
                            "memory_get",
                            "memory_save",
                            "fetch_latest_financial_news",
                            "push_wechat_message");
            assertThat(agent.getSkillRepositories())
                    .anySatisfy(repository -> {
                        assertThat(repository.getAllSkillNames())
                                .contains("stock-buy-fit-analysis");
                        assertThat(repository.getSkill("stock-buy-fit-analysis").getDescription())
                                .contains("分析一只股票是否适合买入");
                    });
            assertThat(agent.getSubagentAgentManager().hasAgent("financial-news")).isTrue();
            assertThat(agent.getSubagentAgentManager().hasAgent("pushplus")).isTrue();
            assertThat(agent.getSubagentAgentManager().getDeclaration("financial-news"))
                    .get()
                    .extracting(declaration -> declaration.getTools())
                    .isEqualTo(java.util.List.of("fetch_latest_financial_news"));
            assertThat(agent.getSubagentAgentManager().getDeclaration("pushplus"))
                    .get()
                    .extracting(declaration -> declaration.getTools())
                    .isEqualTo(java.util.List.of("push_wechat_message"));

            try (HarnessAgent financialNewsAgent = (HarnessAgent) agent.getSubagentAgentManager()
                    .createAgent("financial-news", RuntimeContext.builder()
                            .userId("user-001")
                            .sessionId("session-001")
                            .build())) {
                assertThat(financialNewsAgent.getToolkit().getToolNames())
                        .contains("fetch_latest_financial_news");
                assertThat(financialNewsAgent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
            }
            try (HarnessAgent pushPlusAgent = (HarnessAgent) agent.getSubagentAgentManager()
                    .createAgent("pushplus", RuntimeContext.builder()
                            .userId("user-001")
                            .sessionId("session-002")
                            .build())) {
                assertThat(pushPlusAgent.getToolkit().getToolNames())
                        .contains("push_wechat_message");
            }
        }
    }
}
