package com.example.springbootdemo.handler;

import com.example.springbootdemo.service.AgentChatService.AgentChatException;
import com.example.springbootdemo.service.RecoverableIndexPushTaskService.TaskConflictException;
import com.example.springbootdemo.service.RecoverableIndexPushTaskService.TaskNotFoundException;
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

    @ExceptionHandler(TaskNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleTaskNotFound(TaskNotFoundException exception) {
        return Map.of("error", "任务不存在", "message", exception.getMessage());
    }

    @ExceptionHandler(TaskConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleTaskConflict(TaskConflictException exception) {
        return Map.of("error", "任务状态冲突", "message", exception.getMessage());
    }
}
