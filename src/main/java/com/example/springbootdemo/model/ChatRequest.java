package com.example.springbootdemo.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "message must not be blank") String message,
        @NotBlank(message = "userId must not be blank") String userId,
        @NotBlank(message = "sessionId must not be blank") String sessionId) {
}
