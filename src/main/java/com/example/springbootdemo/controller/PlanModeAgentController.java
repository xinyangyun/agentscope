package com.example.springbootdemo.controller;

import com.example.springbootdemo.service.PlanModeChatService;
import com.example.springbootdemo.service.PlanModeChatService.PlanModeChatResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlanModeAgentController {

    private final PlanModeChatService chatService;

    public PlanModeAgentController(PlanModeChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat/v2", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatV2Response chat(@Valid @RequestBody ChatV2Request request) {
        PlanModeChatResult result = chatService.chat(
                request.message(),
                request.userId(),
                request.sessionId(),
                Boolean.TRUE.equals(request.approvePlan()));
        return new ChatV2Response(
                result.message(),
                request.userId(),
                request.sessionId(),
                result.mode(),
                result.planFile());
    }

    public record ChatV2Request(
            @NotBlank(message = "message must not be blank") String message,
            @NotBlank(message = "userId must not be blank") String userId,
            @NotBlank(message = "sessionId must not be blank") String sessionId,
            Boolean approvePlan) {
    }

    public record ChatV2Response(
            String message,
            String userId,
            String sessionId,
            String mode,
            String planFile) {
    }
}
