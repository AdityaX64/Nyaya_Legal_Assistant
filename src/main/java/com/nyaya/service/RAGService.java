package com.nyaya.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyaya.dto.*;
import com.nyaya.entity.LegalQuery;
import com.nyaya.repository.LegalQueryRepository;
import com.nyaya.service.chunking.LegalSectionChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Core RAG service: document ingestion, multi-section retrieval, chat memory, and query auditing
@Service
public class RAGService {

    private static final Logger log = LoggerFactory.getLogger(RAGService.class);
    
    // Pattern to detect section references in queries (e.g. "Section 303", "Sec 173")
    private static final Pattern SECTION_PATTERN = Pattern.compile("(?i)\\b(?:section|sec\\.?)\\s*(\\d{1,3}[A-Z]?)\\b");
    
    // Pattern to extract cited section numbers from model outputs (e.g. "[BNS Section 303]")
    private static final Pattern CITED_SECTION_OUTPUT = Pattern.compile("\\[(?:BNS|BNSS)?\\s*(?:Section|Sec\\.?)\\s*(\\d{1,3}[A-Z]?)\\]", Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final LegalSectionChunker legalSectionChunker;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMemory chatMemory;
    private final LegalQueryRepository legalQueryRepository;
    private EvalReport lastEvalReport;

    public RAGService(ChatClient chatClient,
                      VectorStore vectorStore,
                      LegalSectionChunker legalSectionChunker,
                      JdbcTemplate jdbcTemplate,
                      ObjectMapper objectMapper,
                      ChatMemory chatMemory,
                      LegalQueryRepository legalQueryRepository) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.legalSectionChunker = legalSectionChunker;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.chatMemory = chatMemory;
        this.legalQueryRepository = legalQueryRepository;
    }

    // Ingest bundled BNS and BNSS sample PDFs from data/sources/
    public int ingestVectorStore() {

        File dir = new File("data/sources");
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalStateException("data/sources directory not found.");
        }
        File[] pdfs = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) {
            throw new IllegalStateException("No sample PDFs found in data/sources.");
        }

        int totalIndexed = 0;
        for (File pdf : pdfs) {
            totalIndexed += ingestPdf(new FileSystemResource(pdf), pdf.getName());
        }
        return totalIndexed;
    }

    // Ingest custom uploaded PDF file

    public int ingestFile(MultipartFile file, String filename) {


        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            return ingestPdf(resource, filename);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read uploaded PDF: " + e.getMessage(), e);
        }
    }

    // Read PDF pages, chunk sections, and store in pgvector
    public int ingestPdf(Resource resource, String filename) {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource);
        List<Document> pages = reader.read();
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("No readable pages in " + filename);
        }

        String actCode = DocumentIngestionService.detectAct(filename, pages);
        String actName = "BNSS".equalsIgnoreCase(actCode)
                ? "Bharatiya Nagarik Suraksha Sanhita, 2023"
                : "Bharatiya Nyaya Sanhita, 2023";

        List<Document> chunks = legalSectionChunker.chunk(pages, actCode, actName, filename);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("No statutory sections found in " + filename);
        }

        // Clean existing records for this file to avoid duplicates
        try {
            jdbcTemplate.update("delete from vector_store where metadata->>'source_file' = ?", filename);
        } catch (Exception ignored) {}

        // Ingest in batches of 30 to avoid socket buffer exhaustion on large documents
        int batchSize = 30;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            vectorStore.add(chunks.subList(i, end));
        }
        log.info("Indexed {} chunks for {}", chunks.size(), filename);
        return chunks.size();
    }

    // Convenience askAI overloads
    public String askAI(String prompt) {
        return askAIWithSources(prompt, "ALL", "default-session").answer();
    }

    public String askAI(String prompt, String conversationId) {
        return askAIWithSources(prompt, "ALL", conversationId).answer();
    }

    public ChatResponse askAIWithSources(String prompt, String actFilter) {
        return askAIWithSources(prompt, actFilter, "default-session");
    }

    // Full RAG flow: retrieval, context injection, LLM call, and audit logging
    public ChatResponse askAIWithSources(String prompt, String actFilter, String conversationId) {

        long start = System.currentTimeMillis();
        String convId = (conversationId == null || conversationId.isBlank()) ? "default-session" : conversationId;

        // Step 1: Multi-section aware retrieval
        List<Document> docs = retrieveRelevantDocuments(prompt, actFilter);
        if (docs.isEmpty()) {
            throw new CorpusNotReadyException();
        }

        String stuffedPrompt = buildPromptWithContext(docs);


        String answer = chatClient.prompt()
                .system(stuffedPrompt)
                .user(prompt)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(convId).build())
                .call()
                .content();

        long latency = System.currentTimeMillis() - start;
        List<SourceReference> sources = extractSources(docs);
        List<String> citedSections = extractCitedSections(answer);

        try {
            legalQueryRepository.save(new LegalQuery(convId, prompt, answer, actFilter, latency, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("Could not save query to audit log: {}", e.getMessage());
        }

        return new ChatResponse(answer, sources, latency, citedSections, convId);
    }

    // Real-time streaming response via Server-Sent Events (SSE)
    public Flux<String> streamAI(String prompt, String actFilter, String conversationId) {
        String convId = (conversationId == null || conversationId.isBlank()) ? "default-session" : conversationId;
        List<Document> docs = retrieveRelevantDocuments(prompt, actFilter);
        if (docs.isEmpty()) throw new CorpusNotReadyException();

        return chatClient.prompt()
                .system(buildPromptWithContext(docs))
                .user(prompt)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(convId).build())
                .stream()
                .content();
    }

    // Formats retrieved statutory documents and binds them into system prompt template
    private String buildPromptWithContext(List<Document> docs) {
        String context = docs.stream()
                .map(this::formatDocumentForContext)
                .collect(Collectors.joining("\n\n---\n\n"));

        String template = """
                You are Nyaya, an expert legal research assistant for the Bharatiya Nyaya Sanhita, 2023 (BNS)
                and the Bharatiya Nagarik Suraksha Sanhita, 2023 (BNSS).
                
                Rules:
                - Answer strictly and directly using the statute extracts provided in the context below.
                - Keep your answer clear, concise, and structured with bullet points. Avoid filler text.
                - Maintain conversational awareness of previous messages in this dialogue.
                - Cite relevant sections using [BNS Section N] or [BNSS Section N].
                - When comparing offences (e.g. culpable homicide vs murder, robbery vs dacoity), clearly distinguish their elements and cite each section.
                - Distinguish substantive criminal law (BNS) from criminal procedure (BNSS).
                - If the context does not contain the answer (e.g. online appeal procedure under BNS), say "The indexed statute text does not establish this detail." Do NOT hallucinate.
                
                Context:
                {context}
                """;

        return new PromptTemplate(template).render(Map.of("context", context));
    }


    // Multi-section retrieval with diversity ranking (prevents single-topic clustering)
    public List<Document> retrieveRelevantDocuments(String prompt, String actFilter) {
        if (count() == 0) return Collections.emptyList();

        Set<String> explicitSections = extractSectionsFromQuery(prompt);
        String resolvedAct = resolveActFilter(prompt, actFilter);

        // 1. Primary vector similarity search
        List<Document> primaryDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(prompt)
                        .topK(10)
                        .similarityThreshold(0.12)
                        .build());

        // 2. Fetch comparative sections if query contrasts two offenses
        List<Document> comparativeDocs = retrieveComparativeConcepts(prompt);

        List<Document> merged = new ArrayList<>();
        if (primaryDocs != null) merged.addAll(primaryDocs);
        merged.addAll(comparativeDocs);

        // 3. Filter by act (BNS/BNSS) if specified
        if (!"ALL".equalsIgnoreCase(resolvedAct)) {
            merged = merged.stream()
                    .filter(d -> resolvedAct.equalsIgnoreCase(stringValue(d.getMetadata().get("act_code"), "")))
                    .toList();
        }

        // 4. Apply diversity selection across sections (limit to 4 most relevant to minimize prompt latency)
        return selectDiverseSections(merged, explicitSections, 4);
    }

    // Keyword helper to retrieve comparative sections for common contrast queries

    private List<Document> retrieveComparativeConcepts(String prompt) {
        String lower = prompt.toLowerCase();
        List<Document> additional = new ArrayList<>();

        if (lower.contains("culpable homicide") && (lower.contains("murder") || lower.contains("differ"))) {
            fetchCandidates("culpable homicide section 100", additional);
            fetchCandidates("murder section 101", additional);
        } else if (lower.contains("robbery") && lower.contains("dacoity")) {
            fetchCandidates("robbery section 309", additional);
            fetchCandidates("dacoity section 310", additional);
        } else if (lower.contains("cognizable") && (lower.contains("bnss") || lower.contains("information") || lower.contains("fir"))) {
            fetchCandidates("BNSS section 173 information in cognizable cases", additional);
        } else if (lower.contains("theft")) {
            fetchCandidates("theft definition section 303 BNS", additional);
        } else if (lower.contains("common intention")) {
            fetchCandidates("common intention section 3 BNS", additional);
        } else if (lower.contains("criminal intimidation")) {
            fetchCandidates("criminal intimidation section 351 BNS", additional);
        }

        return additional;
    }

    private void fetchCandidates(String query, List<Document> target) {
        try {
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(3).similarityThreshold(0.15).build());
            if (docs != null) target.addAll(docs);
        } catch (Exception ignored) {}
    }

    /**
     * Section Diversity Selection (MMR algorithm):
     * Groups candidates by statutory section and ensures representation across distinct sections.
     */
    private List<Document> selectDiverseSections(List<Document> candidates, Set<String> explicitSections, int maxResults) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();

        Map<String, List<Document>> bySection = new LinkedHashMap<>();
        for (Document doc : candidates) {
            String act = stringValue(doc.getMetadata().get("act_code"), "ACT");
            String sec = stringValue(doc.getMetadata().get("section"), "UNKNOWN");
            bySection.computeIfAbsent(act + ":" + sec, k -> new ArrayList<>()).add(doc);
        }

        List<Document> selected = new ArrayList<>();

        // Priority 1: Sections explicitly requested in user question (e.g. "Section 173")
        if (!explicitSections.isEmpty()) {
            for (Map.Entry<String, List<Document>> entry : bySection.entrySet()) {
                String sec = entry.getKey().split(":")[1];
                if (explicitSections.contains(sec)) {
                    selected.add(entry.getValue().get(0));
                }
            }
        }

        // Priority 2: 1 chunk from each unique section (guarantees multi-section diversity)
        for (List<Document> secDocs : bySection.values()) {
            Document top = secDocs.get(0);
            if (!selected.contains(top) && selected.size() < maxResults) {
                selected.add(top);
            }
        }

        // Priority 3: Fill remaining slots with secondary chunks
        for (List<Document> secDocs : bySection.values()) {
            for (int i = 1; i < secDocs.size(); i++) {
                Document doc = secDocs.get(i);
                if (!selected.contains(doc) && selected.size() < maxResults) {
                    selected.add(doc);
                }
            }
        }

        return selected;
    }

    private String formatDocumentForContext(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String act = stringValue(meta.get("act_code"), "BNS");
        String section = stringValue(meta.get("section"), "");
        String title = stringValue(meta.get("title"), "");
        String chapter = stringValue(meta.get("chapter"), "");
        int page = intValue(meta.get("page_start"), intValue(meta.get("page_number"), 0));

        return String.format("[%s Section %s%s | Chapter: %s | Page: %d]\n%s",
                act, section, title.isBlank() ? "" : ": " + title, chapter, page, doc.getText());
    }

    private List<SourceReference> extractSources(List<Document> matches) {
        Map<String, SourceReference> unique = new LinkedHashMap<>();
        for (Document doc : matches) {
            Map<String, Object> meta = doc.getMetadata();
            String text = doc.getText() == null ? "" : doc.getText();
            String act = stringValue(meta.get("act_code"), "BNS");
            String sec = stringValue(meta.get("section"), "");
            String title = stringValue(meta.get("title"), "");
            String file = stringValue(meta.get("source_file"), "");
            int pStart = intValue(meta.get("page_start"), intValue(meta.get("page_number"), 0));
            int pEnd = intValue(meta.get("page_end"), pStart);

            String excerpt = text.length() <= 450 ? text : text.substring(0, 450) + "…";
            SourceReference ref = new SourceReference(act, file, sec, title, pStart, pEnd, excerpt);
            unique.putIfAbsent(act + ":" + sec + ":" + pStart, ref);
        }
        return new ArrayList<>(unique.values());
    }

    private Set<String> extractSectionsFromQuery(String query) {
        Set<String> set = new HashSet<>();
        Matcher m = SECTION_PATTERN.matcher(query);
        while (m.find()) set.add(m.group(1));
        return set;
    }

    private List<String> extractCitedSections(String answer) {
        if (answer == null) return Collections.emptyList();
        Set<String> set = new LinkedHashSet<>();
        Matcher m = CITED_SECTION_OUTPUT.matcher(answer);
        while (m.find()) set.add(m.group(1));
        return new ArrayList<>(set);
    }

    private String resolveActFilter(String prompt, String actFilter) {
        if (actFilter != null && !actFilter.isBlank() && !"ALL".equalsIgnoreCase(actFilter)) {
            return actFilter.toUpperCase();
        }
        String p = prompt.toLowerCase();
        if (p.contains("under the bnss") || p.contains("in bnss") || p.contains("under bnss")) return "BNSS";
        if (p.contains("under the bns") || p.contains("in bns") || p.contains("under bns")) return "BNS";
        return "ALL";
    }

    // ========================================================================
    // 4. HISTORY, STATUS & EVALUATION BENCHMARKS
    // ========================================================================

    public List<LegalQuery> getRecentQueries() {
        return legalQueryRepository.findTop20ByOrderByCreatedAtDesc();
    }

    public List<LegalQuery> getConversationHistory(String conversationId) {
        return legalQueryRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    public int count() {
        try {
            Integer count = jdbcTemplate.queryForObject("select count(*) from vector_store", Integer.class);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public boolean isDatabaseAvailable() {
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // Run automated evaluations against evals/questions.json
    public synchronized EvalReport runBenchmarkEvals() {

        File questionsFile = new File("evals/questions.json");
        if (!questionsFile.exists()) {
            throw new IllegalStateException("evals/questions.json not found.");
        }

        List<Map<String, Object>> benchmarkCases;
        try {
            benchmarkCases = objectMapper.readValue(questionsFile, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse evals/questions.json", e);
        }

        List<EvalResult> results = new ArrayList<>();
        long totalLatency = 0;
        int passedCount = 0;

        for (Map<String, Object> testCase : benchmarkCases) {
            String id = (String) testCase.get("id");
            String question = (String) testCase.get("question");
            @SuppressWarnings("unchecked")
            List<String> expectedSections = (List<String>) testCase.getOrDefault("expected_sections", Collections.emptyList());
            String expectedAct = (String) testCase.get("expected_act");
            String expectedBehavior = (String) testCase.get("expected_behavior");

            long start = System.currentTimeMillis();
            try {
                ChatResponse response = askAIWithSources(question, "ALL", "eval-session-" + id);
                long latency = System.currentTimeMillis() - start;
                totalLatency += latency;

                Set<String> foundSections = new HashSet<>(response.citedSections());
                for (SourceReference src : response.sources()) {
                    if (src.section() != null && !src.section().isBlank()) foundSections.add(src.section());
                }
                Matcher m = SECTION_PATTERN.matcher(response.answer());
                while (m.find()) foundSections.add(m.group(1));

                boolean passed = true;
                List<String> reasons = new ArrayList<>();

                if (expectedSections != null && !expectedSections.isEmpty()) {
                    for (String exp : expectedSections) {
                        if (!foundSections.contains(exp)) {
                            passed = false;
                            reasons.add("Missing expected section " + exp);
                        }
                    }
                }

                if (expectedAct != null) {
                    boolean actMatch = response.answer().toUpperCase().contains(expectedAct.toUpperCase())
                            || response.sources().stream().anyMatch(s -> expectedAct.equalsIgnoreCase(s.actCode()));
                    if (!actMatch) {
                        passed = false;
                        reasons.add("Expected Act " + expectedAct + " not cited.");
                    }
                }

                if (expectedBehavior != null && !expectedBehavior.isBlank()) {
                    String lower = response.answer().toLowerCase();
                    boolean refused = lower.contains("does not establish")
                            || lower.contains("not establish")
                            || lower.contains("not contain")
                            || lower.contains("not provide");
                    if (!refused) {
                        passed = false;
                        reasons.add("Expected refusal not triggered.");
                    }
                }

                if (passed) passedCount++;

                results.add(new EvalResult(
                        id, question, expectedSections, expectedAct,
                        new ArrayList<>(foundSections), passed,
                        passed ? null : String.join("; ", reasons),
                        latency, response.answer()
                ));
            } catch (Exception ex) {
                long latency = System.currentTimeMillis() - start;
                totalLatency += latency;
                results.add(new EvalResult(
                        id, question, expectedSections, expectedAct,
                        Collections.emptyList(), false,
                        "Exception: " + ex.getMessage(), latency, "Error"
                ));
            }
        }

        double passRate = (double) passedCount / benchmarkCases.size() * 100.0;
        long avgLatency = results.isEmpty() ? 0 : totalLatency / results.size();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        this.lastEvalReport = new EvalReport(benchmarkCases.size(), passedCount, passRate, avgLatency, results, timestamp);
        saveMarkdownEvalReport(lastEvalReport);
        return this.lastEvalReport;
    }

    public EvalReport getLastEvalReport() {
        return lastEvalReport;
    }

    private void saveMarkdownEvalReport(EvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Nyaya Legal RAG — Automated Evaluation Report\n\n");
        sb.append("**Evaluation Date:** ").append(report.timestamp()).append("\n\n");
        sb.append("- **Total Tests:** ").append(report.totalQuestions()).append("\n");
        sb.append("- **Pass Rate:** **").append(String.format("%.1f%%", report.passRate())).append("**\n");
        sb.append("- **Average Latency:** ").append(report.averageLatencyMs()).append(" ms\n\n");

        sb.append("| Test ID | Question | Expected § | Detected § | Status | Latency |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (EvalResult r : report.results()) {
            sb.append("| `").append(r.id()).append("` | ")
                    .append(r.question().replace("|", "\\|")).append(" | ")
                    .append(r.expectedSections().isEmpty() ? "Refusal check" : String.join(", ", r.expectedSections())).append(" | ")
                    .append(r.foundSections().isEmpty() ? "None" : String.join(", ", r.foundSections())).append(" | ")
                    .append(r.passed() ? "PASSED" : "FAILED").append(" | ")
                    .append(r.latencyMs()).append(" ms |\n");
        }

        try (FileWriter writer = new FileWriter("evals/EVAL_REPORT.md")) {
            writer.write(sb.toString());
        } catch (IOException ignored) {}
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public static class CorpusNotReadyException extends RuntimeException {
        public CorpusNotReadyException() {
            super("No legal text has been indexed yet. Upload or index the BNS/BNSS PDFs first.");
        }
    }
}
