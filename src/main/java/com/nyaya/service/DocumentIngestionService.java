package com.nyaya.service;

import com.nyaya.service.chunking.LegalSectionChunker;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class DocumentIngestionService {

    private final LegalSectionChunker legalSectionChunker;

    public DocumentIngestionService(LegalSectionChunker legalSectionChunker) {
        this.legalSectionChunker = legalSectionChunker;
    }

    public List<Document> readAndSplit(MultipartFile file, String filename) {
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            return readAndSplit(resource, filename);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read the uploaded PDF: " + exception.getMessage(), exception);
        }
    }

    public List<Document> readAndSplit(Resource resource, String filename) {
        List<Document> pages = new PagePdfDocumentReader(resource).read();
        if (pages == null || pages.isEmpty() || pages.stream().allMatch(page -> page.getText() == null || page.getText().isBlank())) {
            throw new IllegalArgumentException("No selectable text found in " + filename + ". OCR the scanned PDF first.");
        }

        String actCode = detectAct(filename, pages);
        String actName = switch (actCode) {
            case "BNS" -> "Bharatiya Nyaya Sanhita, 2023";
            case "BNSS" -> "Bharatiya Nagarik Suraksha Sanhita, 2023";
            default -> "Indian Statutory Law";
        };

        List<Document> chunks = legalSectionChunker.chunk(pages, actCode, actName, filename);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("The PDF did not contain any indexable statutory sections.");
        }
        return chunks;
    }

    public static String detectAct(String filename, List<Document> pages) {
        String name = filename.toLowerCase();
        if (name.contains("bnss") || name.contains("nagarik") || name.contains("250884")) return "BNSS";
        if (name.contains("bns") || name.contains("nyaya") || name.contains("250883")) return "BNS";
        String firstPages = pages.stream().limit(5).map(Document::getText).filter(text -> text != null)
                .reduce("", (left, right) -> left + " " + right).toUpperCase().replaceAll("[^A-Z]", "");
        if (firstPages.contains("NAGARIKSURAKSHASANHITA")) return "BNSS";
        if (firstPages.contains("BHARATIYANYAYASANHITA")) return "BNS";
        return "BNS"; // default to BNS if primary topic
    }
}
