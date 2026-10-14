package com.example.springbootdemo.controller;

import com.example.springbootdemo.service.PlanModeChatService;
import com.example.springbootdemo.service.PlanModeChatService.PlanNotFoundException;
import com.example.springbootdemo.model.PlanDetails;
import com.example.springbootdemo.model.PlanModeChatResult;
import com.example.springbootdemo.model.PlanTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void getPlanV2ReturnsCurrentPlanAndTasks() throws Exception {
        when(chatService.getPlan("user-001", "session-001"))
                .thenReturn(new PlanDetails(
                        "PLAN",
                        "plans/PLAN.md",
                        "# 执行计划\n\n1. 查询指数数据",
                        true,
                        java.util.List.of(new PlanTask(
                                "task-1", "查询指数", "获取今日指数数据", "pending"))));

        mockMvc.perform(get("/chat/v2/plan")
                        .param("userId", "user-001")
                        .param("sessionId", "session-001"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "userId": "user-001",
                          "sessionId": "session-001",
                          "mode": "PLAN",
                          "planFile": "plans/PLAN.md",
                          "planContent": "# 执行计划\n\n1. 查询指数数据",
                          "awaitingApproval": true,
                          "tasks": [{
                            "id": "task-1",
                            "subject": "查询指数",
                            "description": "获取今日指数数据",
                            "state": "pending"
                          }]
                        }
                        """));
    }

    @Test
    void getPlanV2RejectsBlankSessionId() throws Exception {
        mockMvc.perform(get("/chat/v2/plan")
                        .param("userId", "user-001")
                        .param("sessionId", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPlanV2ReturnsNotFoundForUnknownSession() throws Exception {
        when(chatService.getPlan("user-001", "missing-session"))
                .thenThrow(new PlanNotFoundException("No Plan Mode session was found"));

        mockMvc.perform(get("/chat/v2/plan")
                        .param("userId", "user-001")
                        .param("sessionId", "missing-session"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {
                          "error": "未找到规划会话",
                          "message": "No Plan Mode session was found"
                        }
                        """));
    }
}
