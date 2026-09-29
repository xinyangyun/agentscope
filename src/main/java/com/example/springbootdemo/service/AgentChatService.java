package com.example.springbootdemo.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;

@Service
public class AgentChatService {

    private final HarnessAgent agent;

    public AgentChatService(HarnessAgent agent) {
        this.agent = agent;
    }

    public String chat(String message, String userId, String sessionId) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        Msg response = agent.call(new UserMessage(message), context).block();
        if (response == null) {
            throw new IllegalStateException("Agent returned no response");
        }
        return response.getTextContent();
    }
}
