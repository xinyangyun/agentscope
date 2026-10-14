package com.example.springbootdemo.model;

import java.util.List;

public record PlanDetails(
        String mode,
        String planFile,
        String planContent,
        boolean awaitingApproval,
        List<PlanTask> tasks) {
}
