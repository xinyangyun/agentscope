package com.example.springbootdemo.controller;

import com.example.springbootdemo.service.PlanModeChatService;
import com.example.springbootdemo.service.PlanModeChatService.PlanModeChatResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlanModeAgentController.class)
class PlanModeAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlanModeChatService chatService;

    @Test
    void chatV2ReturnsPlanModeState() throws Exception {
        when(chatService.chat(eq("重构订单模块"), eq("user-001"), eq("session-001"), eq(false)))
                .thenReturn(new PlanModeChatResult(
                        "计划已经写好，请确认后执行。", "PLAN", "plans/PLAN.md"));

        mockMvc.perform(post("/chat/v2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "重构订单模块",
                                  "userId": "user-001",
                                  "sessionId": "session-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "message": "计划已经写好，请确认后执行。",
                          "userId": "user-001",
                          "sessionId": "session-001",
                          "mode": "PLAN",
                          "planFile": "plans/PLAN.md"
                        }
                        """));
    }

    @Test
    void chatV2PassesExplicitApproval() throws Exception {
        when(chatService.chat(eq("批准并执行"), eq("user-001"), eq("session-001"), eq(true)))
                .thenReturn(new PlanModeChatResult(
                        "计划已执行。", "BUILD", "plans/PLAN.md"));

        mockMvc.perform(post("/chat/v2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "批准并执行",
                                  "userId": "user-001",
                                  "sessionId": "session-001",
                                  "approvePlan": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "message": "计划已执行。",
                          "mode": "BUILD",
                          "planFile": "plans/PLAN.md"
                        }
                        """));
    }

    @Test
    void chatV2RejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/chat/v2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "",
                                  "userId": "user-001",
                                  "sessionId": "session-001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
