package com.example.springbootdemo.model;

import java.time.Instant;

public record IndexPushTaskResponse(
        String taskId,
        String index,
        String title,
        String status,
        boolean interruptRequested,
        String indexResult,
        String pushResult,
        String error,
        Instant createdAt,
        Instant updatedAt) {
}
