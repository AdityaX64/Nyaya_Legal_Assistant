package com.nyaya.chunking;

import com.nyaya.service.chunking.LegalSectionChunker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Tests statutory parsing: section splitting, marginal titles, and cross-references
public class LegalSectionChunkerTest {

    private final LegalSectionChunker chunker = new LegalSectionChunker();

    // Verifies multi-section splitting and cross-reference extraction on same page
    @Test
    void shouldExtractSectionsAndChapters() {
        String pageText = """
                CHAPTER VI
                OF OFFENCES AFFECTING THE HUMAN BODY
                
                Culpable homicide.   100. Whoever causes death by doing an act with the intention of causing death,
                or with the intention of causing such bodily injury as is likely to cause death,
                or with the knowledge that he is likely by such act to cause death, commits the offence of culpable homicide.
                
                Murder.              101. Except in the cases hereinafter excepted, culpable homicide is murder,
                if the act by which the death is caused is done with the intention of causing death.
                This is punishable under section 103.
                """;

        Document page = new Document(pageText, Map.of("page_number", 31));
        List<Document> chunks = chunker.chunk(List.of(page), "BNS", "Bharatiya Nyaya Sanhita, 2023", "bns.pdf");

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2, "Expected at least 2 distinct section chunks");

        Document sec100 = chunks.stream()
                .filter(c -> "100".equals(c.getMetadata().get("section")))
                .findFirst().orElse(null);
        assertNotNull(sec100, "Section 100 should be parsed");
        assertEquals("BNS", sec100.getMetadata().get("act_code"));
        assertTrue(sec100.getText().contains("Section 100: Culpable homicide"));
        assertTrue(sec100.getText().contains("commits the offence of culpable homicide"));

        Document sec101 = chunks.stream()
                .filter(c -> "101".equals(c.getMetadata().get("section")))
                .findFirst().orElse(null);
        assertNotNull(sec101, "Section 101 should be parsed");
        assertTrue(sec101.getText().contains("Section 101: Murder"));
        assertTrue(sec101.getMetadata().containsKey("cross_references"));
        assertTrue(sec101.getMetadata().get("cross_references").toString().contains("103"));
    }

    // Verifies subsection extraction and breadcrumb formatting
    @Test
    void shouldHandleTheftSection303() {

        String pageText = """

                CHAPTER XVII
                OF OFFENCES AGAINST PROPERTY
                
                303. (1) Whoever, intending to take dishonestly any movable property out of the possession of any person
                without that person's consent, moves that property in order to such taking, is said to commit theft.
                """;

        Document page = new Document(pageText, Map.of("page_number", 78));
        List<Document> chunks = chunker.chunk(List.of(page), "BNS", "Bharatiya Nyaya Sanhita, 2023", "bns.pdf");

        assertEquals(1, chunks.size());
        Document chunk = chunks.get(0);
        assertEquals("303", chunk.getMetadata().get("section"));
        assertEquals("Theft", chunk.getMetadata().get("title"));
        assertTrue(chunk.getText().contains("[Bharatiya Nyaya Sanhita, 2023 | CHAPTER XVII"));
        assertTrue(chunk.getText().contains("Section 303: Theft"));
    }

    // Verifies that large statutory sections are safely bounded within chunk budget
    @Test
    void shouldSplitOversizedSection() {
        String longText = "Whoever commits an offense punishable under this section. ".repeat(100);
        String pageText = "CHAPTER I\nPRELIMINARY\n\n2. Definitions. " + longText;

        Document page = new Document(pageText, Map.of("page_number", 2));
        List<Document> chunks = chunker.chunk(List.of(page), "BNS", "Bharatiya Nyaya Sanhita, 2023", "bns.pdf");

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2, "Expected large section to be split into multiple chunks");
        for (Document c : chunks) {
            assertTrue(c.getText().length() <= 3000, "Each chunk must stay within safe token/character budget");
        }
    }
}
