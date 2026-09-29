package com.example.springbootdemo.controller;

import com.example.springbootdemo.service.AgentChatService;
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

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentChatService chatService;

    @Test
    void chatReturnsAgentReply() throws Exception {
        when(chatService.chat(eq("你好"), eq("user-001"), eq("session-001")))
                .thenReturn("你好，我是 AI 助手。");

        mockMvc.perform(post("/api/chat")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "你好",
                                  "userId": "user-001",
                                  "sessionId": "session-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "message": "你好，我是 AI 助手。",
                          "userId": "user-001",
                          "sessionId": "session-001"
                        }
                        """));
    }

    @Test
    void chatRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/chat")
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
