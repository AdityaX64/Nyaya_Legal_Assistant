package com.nyaya.controller;

import com.nyaya.dto.ChatRequest;
import com.nyaya.dto.ChatResponse;
import com.nyaya.dto.EvalReport;
import com.nyaya.dto.IngestResponse;
import com.nyaya.entity.LegalQuery;
import com.nyaya.service.RAGService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// REST controller for chat, streaming, history, and PDF ingestion
@RestController
public class ChatController {

    private final RAGService ragService;

    public ChatController(RAGService ragService) {
        this.ragService = ragService;
    }

    // Q&A endpoint with citations and conversation memory
    @PostMapping({"/chat", "/api/chat"})
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return ragService.askAIWithSources(request.question().trim(), request.actFilter(), request.conversationId());
    }


    // Server-Sent Events (SSE) streaming for fast time-to-first-token
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@Valid @RequestBody ChatRequest request) {
        return ragService.streamAI(request.question().trim(), request.actFilter(), request.conversationId());
    }

    // System health and vector store status
    @GetMapping({"/health", "/api/health"})
    public Map<String, Object> health() {
        boolean postgres = ragService.isDatabaseAvailable();
        int chunks = postgres ? ragService.count() : 0;
        return Map.of(
                "status", postgres ? "ok" : "degraded",
                "chunks", chunks,
                "postgres", postgres,
                "vectorStore", "pgvector"
        );
    }

    // Recent queries audit log
    @GetMapping({"/history", "/api/history"})
    public List<LegalQuery> getRecentQueries() {
        return ragService.getRecentQueries();
    }

    // Conversation thread by session ID
    @GetMapping({"/history/{conversationId}", "/api/history/{conversationId}"})
    public List<LegalQuery> getConversationHistory(@PathVariable String conversationId) {
        return ragService.getConversationHistory(conversationId);
    }

    // Ingest bundled sample PDFs (BNS & BNSS)
    @PostMapping({"/ingest/samples", "/api/ingest/samples"})
    public ResponseEntity<IngestResponse> ingestSamples() {
        int indexed = ragService.ingestVectorStore();
        return ResponseEntity.ok(new IngestResponse(
                List.of("250883_english_01042024_nayaa_sanhita.pdf", "250884_2_english_01042024.pdf"),
                indexed,
                ragService.count()
        ));
    }

    // Upload and ingest custom PDF documents
    @PostMapping(value = {"/ingest", "/api/ingest"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> ingest(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile singleFile) {

        List<MultipartFile> uploads = new ArrayList<>();
        if (files != null) uploads.addAll(files);
        if (singleFile != null) uploads.add(singleFile);
        if (uploads.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload one or more PDF files.");
        }

        List<String> filenames = new ArrayList<>();
        int indexed = 0;
        for (MultipartFile upload : uploads) {
            if (upload.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty.");
            String original = upload.getOriginalFilename() == null ? "bns.pdf" : upload.getOriginalFilename();
            String cleanName = Paths.get(original.replace('\\', '/')).getFileName().toString();
            indexed += ragService.ingestFile(upload, cleanName);
            filenames.add(cleanName);
        }

        return ResponseEntity.ok(new IngestResponse(filenames, indexed, ragService.count()));
    }

    // Run automated benchmark evaluations
    @PostMapping({"/evals/run", "/api/evals/run"})
    public ResponseEntity<EvalReport> runEvals() {
        EvalReport report = ragService.runBenchmarkEvals();
        return ResponseEntity.ok(report);
    }

    // Get last evaluation report results
    @GetMapping({"/evals/results", "/api/evals/results"})
    public ResponseEntity<EvalReport> getEvalResults() {
        EvalReport report = ragService.getLastEvalReport();
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }
}


