package com.nyaya.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Incoming user chat request
public record ChatRequest(
        @NotBlank @Size(min = 2, max = 2000) String question,
        String actFilter,      // "BNS", "BNSS", or "ALL"
        String conversationId  // session id for chat memory
) {
    public ChatRequest(String question) {
        this(question, "ALL", "default-session");
    }

    public ChatRequest(String question, String actFilter) {
        this(question, actFilter, "default-session");
    }
}


