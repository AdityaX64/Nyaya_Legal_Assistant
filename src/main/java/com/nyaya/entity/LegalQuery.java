package com.nyaya.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// Stores Q&A history and latency metrics in PostgreSQL
@Entity
@Table(name = "legal_queries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LegalQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Chat session identifier
    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "question", columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(name = "answer", columnDefinition = "TEXT", nullable = false)
    private String answer;

    // Filter used: BNS, BNSS, or ALL
    @Column(name = "act_filter")
    private String actFilter;

    // Response latency in ms
    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // Constructor without id (database auto-generates id)
    public LegalQuery(String conversationId, String question, String answer, String actFilter, Long latencyMs, LocalDateTime createdAt) {
        this.conversationId = conversationId;
        this.question = question;
        this.answer = answer;
        this.actFilter = actFilter;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }
}

