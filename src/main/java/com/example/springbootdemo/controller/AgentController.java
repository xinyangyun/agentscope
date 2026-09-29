package com.example.springbootdemo.controller;

import com.example.springbootdemo.service.AgentChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentChatService chatService;

    public AgentController(AgentChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String reply = chatService.chat(request.message(), request.userId(), request.sessionId());
        return new ChatResponse(reply, request.userId(), request.sessionId());
    }

    public record ChatRequest(
            @NotBlank(message = "message must not be blank") String message,
            @NotBlank(message = "userId must not be blank") String userId,
            @NotBlank(message = "sessionId must not be blank") String sessionId) {
    }

    public record ChatResponse(String message, String userId, String sessionId) {
    }
}
