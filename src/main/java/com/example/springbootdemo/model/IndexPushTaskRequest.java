package com.example.springbootdemo.model;

import jakarta.validation.constraints.NotBlank;

public record IndexPushTaskRequest(
        @NotBlank(message = "index must not be blank") String index,
        String title,
        boolean interruptAfterQuery) {
}
