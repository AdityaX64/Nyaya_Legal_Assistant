package com.nyaya.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyaya.repository.LegalQueryRepository;
import com.nyaya.service.chunking.LegalSectionChunker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Unit tests for RAGService: empty corpus guard, diversity ranking, and act filters
@ExtendWith(MockitoExtension.class)
public class RAGServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private LegalSectionChunker legalSectionChunker;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ChatMemory chatMemory;
    @Mock
    private LegalQueryRepository legalQueryRepository;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(
                chatClient,
                vectorStore,
                legalSectionChunker,
                jdbcTemplate,
                new ObjectMapper(),
                chatMemory,
                legalQueryRepository
        );
    }

    // Verify exception thrown if database has no indexed chunks
    @Test
    public void testCorpusNotReadyWhenEmpty() {
        when(jdbcTemplate.queryForObject("select count(*) from vector_store", Integer.class)).thenReturn(0);

        assertThrows(RAGService.CorpusNotReadyException.class, () -> {
            ragService.askAI("What is theft?");
        });
    }

    // Verify comparative questions retrieve sections from both offenses (100 and 101)
    @Test
    public void testMultiSectionDiversity() {
        when(jdbcTemplate.queryForObject("select count(*) from vector_store", Integer.class)).thenReturn(100);

        Document doc100 = new Document("Culpable homicide details",
                Map.of("act_code", "BNS", "section", "100", "title", "Culpable homicide"));
        Document doc101 = new Document("Murder details",
                Map.of("act_code", "BNS", "section", "101", "title", "Murder"));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc100, doc101));

        List<Document> docs = ragService.retrieveRelevantDocuments(
                "Explain how culpable homicide differs from murder", "ALL");

        assertFalse(docs.isEmpty());
        assertTrue(docs.stream().anyMatch(d -> "100".equals(d.getMetadata().get("section"))));
        assertTrue(docs.stream().anyMatch(d -> "101".equals(d.getMetadata().get("section"))));
    }

    // Verify act filter restricts results to requested statute
    @Test
    public void testActFilter() {
        when(jdbcTemplate.queryForObject("select count(*) from vector_store", Integer.class)).thenReturn(100);

        Document bnsDoc = new Document("Theft in BNS", Map.of("act_code", "BNS", "section", "303"));
        Document bnssDoc = new Document("Procedure in BNSS", Map.of("act_code", "BNSS", "section", "173"));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(bnsDoc, bnssDoc));

        List<Document> filtered = ragService.retrieveRelevantDocuments("cognizable offences", "BNSS");

        assertEquals(1, filtered.size());
        assertEquals("BNSS", filtered.get(0).getMetadata().get("act_code"));
    }
}


