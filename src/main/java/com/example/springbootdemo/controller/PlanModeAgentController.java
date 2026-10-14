package com.example.springbootdemo.controller;

import com.example.springbootdemo.model.ChatV2Request;
import com.example.springbootdemo.model.ChatV2Response;
import com.example.springbootdemo.model.PlanDetails;
import com.example.springbootdemo.model.PlanModeChatResult;
import com.example.springbootdemo.model.PlanV2Response;
import com.example.springbootdemo.service.PlanModeChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
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

    @GetMapping(value = "/chat/v2/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public PlanV2Response getPlan(
            @RequestParam @NotBlank(message = "userId must not be blank") String userId,
            @RequestParam @NotBlank(message = "sessionId must not be blank") String sessionId) {
        PlanDetails details = chatService.getPlan(userId, sessionId);
        return new PlanV2Response(
                userId,
                sessionId,
                details.mode(),
                details.planFile(),
                details.planContent(),
                details.awaitingApproval(),
                details.tasks());
    }

}
