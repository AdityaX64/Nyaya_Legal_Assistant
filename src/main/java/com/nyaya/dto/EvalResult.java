package com.nyaya.dto;

import java.util.List;

// Evaluation result for an individual benchmark question
public record EvalResult(
        String id,
        String question,
        List<String> expectedSections,
        String expectedAct,
        List<String> foundSections,
        boolean passed,
        String failureReason,
        long latencyMs,
        String sampleAnswer
) {}


