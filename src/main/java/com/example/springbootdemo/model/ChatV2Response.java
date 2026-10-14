package com.example.springbootdemo.model;

public record ChatV2Response(
        String message,
        String userId,
        String sessionId,
        String mode,
        String planFile) {
}
