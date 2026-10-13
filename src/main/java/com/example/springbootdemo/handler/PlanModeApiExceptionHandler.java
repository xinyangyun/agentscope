package com.example.springbootdemo.handler;

import com.example.springbootdemo.service.PlanModeChatService.PlanApprovalException;
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
}
