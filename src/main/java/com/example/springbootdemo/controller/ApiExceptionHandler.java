package com.example.springbootdemo.controller;

import com.example.springbootdemo.service.AgentChatService.AgentChatException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AgentChatException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handleAgentChatException(AgentChatException exception) {
        return Map.of(
                "error", "模型服务调用失败",
                "message", exception.getMessage());
    }
}
