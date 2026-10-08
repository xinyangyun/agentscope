package com.example.springbootdemo.config;

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
    void harnessAgentUsesProcessLocalShortTermMemory() {
        AgentConfiguration configuration = new AgentConfiguration();
        AgentStateStore memoryStore = configuration.shortTermMemoryStore();

        try (HarnessAgent agent = configuration.harnessAgent(
                "assistant",
                "You are a helpful AI assistant.",
                "deepseek-chat",
                "test-api-key",
                "https://api.deepseek.com",
                workspace.toString(),
                memoryStore)) {
            assertThat(memoryStore).isInstanceOf(InMemoryAgentStateStore.class);
            assertThat(agent.getStateStore()).isSameAs(memoryStore);
            assertThat(agent.getToolkit().getToolNames())
                    .doesNotContain("memory_search", "memory_get", "memory_save");
        }
    }
}
