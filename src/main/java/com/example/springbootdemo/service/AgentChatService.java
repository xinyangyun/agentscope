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

        Msg response;
        try {
            response = agent.call(new UserMessage(message), context).block();
        } catch (RuntimeException exception) {
            throw new AgentChatException("Model request failed: " + rootMessage(exception), exception);
        }
        if (response == null) {
            throw new IllegalStateException("Agent returned no response");
        }
        return response.getTextContent();
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    public static class AgentChatException extends RuntimeException {

        public AgentChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
