package com.example.springbootdemo.model;

import java.util.List;

public record PlanV2Response(
        String userId,
        String sessionId,
        String mode,
        String planFile,
        String planContent,
        boolean awaitingApproval,
        List<PlanTask> tasks) {
}
