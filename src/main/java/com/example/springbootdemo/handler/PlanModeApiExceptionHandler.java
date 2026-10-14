package com.example.springbootdemo.handler;

import com.example.springbootdemo.service.PlanModeChatService.PlanApprovalException;
import com.example.springbootdemo.service.PlanModeChatService.PlanNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class PlanModeApiExceptionHandler {

    @ExceptionHandler(PlanApprovalException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handlePlanApprovalException(PlanApprovalException exception) {
        return Map.of(
                "error", "没有待批准的计划",
                "message", exception.getMessage());
    }

    @ExceptionHandler(PlanNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handlePlanNotFoundException(PlanNotFoundException exception) {
        return Map.of(
                "error", "未找到规划会话",
                "message", exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleConstraintViolationException(ConstraintViolationException exception) {
        return Map.of(
                "error", "请求参数校验失败",
                "message", exception.getMessage());
    }
}
