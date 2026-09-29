package com.example.springbootdemo.config;

import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
public class AgentConfiguration {

    @Bean
    public HarnessAgent harnessAgent(
            @Value("${agentscope.agent.name:assistant}") String name,
            @Value("${agentscope.agent.system-prompt:You are a helpful AI assistant.}") String systemPrompt,
            @Value("${agentscope.agent.model:openai:qwen-plus}") String model,
            @Value("${agentscope.agent.workspace:.agentscope/workspace}") String workspace) {
        return HarnessAgent.builder()
                .name(name)
                .sysPrompt(systemPrompt)
                .model(model)
                .workspace(Paths.get(workspace))
                .build();
    }
}
