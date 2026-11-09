package com.example.springbootdemo.controller;

import com.example.springbootdemo.model.IndexPushTaskRequest;
import com.example.springbootdemo.model.IndexPushTaskResponse;
import com.example.springbootdemo.service.RecoverableIndexPushTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecoverableIndexPushTaskController.class)
class RecoverableIndexPushTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecoverableIndexPushTaskService taskService;

    @Test
    void submitsRecoverableTask() throws Exception {
        Instant now = Instant.parse("2026-11-09T08:00:00Z");
        when(taskService.submit(any(IndexPushTaskRequest.class))).thenReturn(
                new IndexPushTaskResponse(
                        "task-001", "上证指数", "今日指数", "QUEUED", false,
                        null, null, null, now, now));

        mockMvc.perform(post("/api/index-push-tasks")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "index": "上证指数",
                                  "title": "今日指数",
                                  "interruptAfterQuery": true
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-001"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }
}
