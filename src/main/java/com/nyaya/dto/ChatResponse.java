package com.nyaya.dto;

import java.util.List;

// Response payload with generated answer and source citations
public record ChatResponse(
        String answer,
        List<SourceReference> sources,
        long latencyMs,
        List<String> citedSections,
        String conversationId
) {
    public ChatResponse(String answer, List<SourceReference> sources) {
        this(answer, sources, 0L, List.of(), "default-session");
    }

    public ChatResponse(String answer, List<SourceReference> sources, long latencyMs, List<String> citedSections) {
        this(answer, sources, latencyMs, citedSections, "default-session");
    }
}


