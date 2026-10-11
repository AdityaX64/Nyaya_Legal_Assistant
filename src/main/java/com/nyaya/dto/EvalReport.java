package com.nyaya.dto;

import java.util.List;

// Summary report for benchmark evaluations
public record EvalReport(
        int totalQuestions,
        int passedQuestions,
        double passRate,
        long averageLatencyMs,
        List<EvalResult> results,
        String timestamp
) {}


