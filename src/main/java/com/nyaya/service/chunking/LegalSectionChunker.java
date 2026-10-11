package com.nyaya.service.chunking;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Hierarchical statutory parser for Indian Gazette PDFs (BNS & BNSS).
// Splits documents by legal section boundaries rather than fixed token lengths.
@Component
public class LegalSectionChunker {

    // Matches Chapter declarations (e.g. "CHAPTER VI\nOF OFFENCES...")
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "(?im)^\\s*(CHAPTER\\s+[IVXLCDM\\d]+)\\s*\\r?\\n\\s*([A-Z\\s,.-]{3,120})");

    // Matches section headers: leading marginal title, section number, or subsection
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:([A-Za-z][A-Za-z\\s'-]{1,35}\\.?)\\s+)?(\\d{1,3}[A-Z]?)\\.\\s*(?:\\((\\d+|[a-zA-Z])\\)\\s*)?(.*)$");

    // Matches trailing marginal notes at end of line
    private static final Pattern MARGINAL_NOTE_TRAILING = Pattern.compile(
            "\\s{4,}([A-Za-z][A-Za-z\\s'-]{2,35})\\.\\s*$");

    // Finds cross-references to other sections (e.g. "under section 103")
    private static final Pattern CROSS_REFERENCE = Pattern.compile(
            "(?i)\\b(?:section|sec\\.?)\\s*(\\d{1,3}[A-Z]?)\\b");

    // Target character budget per chunk (~600 tokens)
    private static final int MAX_CHUNK_CHARS = 2400;

    // Converts raw PDF pages into section-level Document chunks
    public List<Document> chunk(List<Document> pages, String actCode, String actName, String filename) {

        if (pages == null || pages.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Group lines across pages into discrete statutory section objects
        List<ParsedSection> parsedSections = extractSectionsFromPages(pages, actCode, actName, filename);
        List<Document> chunks = new ArrayList<>();

        // 2. Format each section into one or more vector-store ready Documents
        for (ParsedSection sec : parsedSections) {
            chunks.addAll(createChunksForSection(sec));
        }

        return chunks;
    }

    /**
     * Iterates through PDF pages, tracking the current Chapter and Section boundaries.
     */
    private List<ParsedSection> extractSectionsFromPages(List<Document> pages, String actCode, String actName, String filename) {
        List<ParsedSection> sections = new ArrayList<>();
        String currentChapter = "PRELIMINARY";
        ParsedSection currentSection = null;

        for (int p = 0; p < pages.size(); p++) {
            Document pageDoc = pages.get(p);
            String rawText = pageDoc.getText();
            if (rawText == null || rawText.isBlank()) continue;

            int pageNum = getPageNumber(pageDoc.getMetadata(), p + 1);

            // Check if this page introduces a new statutory chapter
            Matcher chapterMatcher = CHAPTER_PATTERN.matcher(rawText);
            if (chapterMatcher.find()) {
                currentChapter = chapterMatcher.group(1).trim() + ": " + chapterMatcher.group(2).trim();
            }

            String[] lines = rawText.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                Matcher secMatcher = SECTION_PATTERN.matcher(line);
                if (secMatcher.find()) {
                    String leadingTitle = secMatcher.group(1);
                    String secNum = secMatcher.group(2);
                    String remainder = secMatcher.group(4);

                    // Verify candidate is an actual section header rather than an internal list item
                    if (isValidSectionCandidate(secNum, leadingTitle, line)) {
                        // Conclude previous section if one was accumulating
                        if (currentSection != null && currentSection.hasContent()) {
                            sections.add(currentSection);
                        }

                        // Determine section title (marginal note)
                        String title = resolveTitle(leadingTitle, remainder, secNum, actCode);
                        currentSection = new ParsedSection(actCode, actName, currentChapter, secNum, title, filename, pageNum);
                        currentSection.appendLine(line);
                        continue;
                    }
                }

                // Accumulate line into active section
                if (currentSection != null) {
                    currentSection.appendLine(line);
                    currentSection.updateEndPage(pageNum);
                }
            }
        }

        // Add final active section
        if (currentSection != null && currentSection.hasContent()) {
            sections.add(currentSection);
        }

        return sections;
    }

    /**
     * Prevents false positives (e.g. "1. death", "2. life imprisonment" within explanations).
     */
    private boolean isValidSectionCandidate(String secNum, String leadingTitle, String line) {
        try {
            int num = Integer.parseInt(secNum.replaceAll("[^0-9]", ""));
            // BNS has 358 sections; BNSS has 531 sections
            if (num < 1 || num > 550) return false;
        } catch (NumberFormatException e) {
            return false;
        }

        // If leading title exists, check it doesn't contain sentence stop words
        if (leadingTitle != null && !leadingTitle.isBlank()) {
            String ltLower = leadingTitle.toLowerCase();
            if (ltLower.contains("under") || ltLower.contains("section") || ltLower.contains("punishable")
                    || ltLower.contains("clause") || ltLower.contains("accordance")) {
                return false;
            }
            return true;
        }

        // Check for standard statutory language indicating the beginning of a section
        String lower = line.toLowerCase();
        return lower.contains("whoever") || lower.contains("the ") || lower.contains("any ")
                || lower.contains("where ") || lower.contains("in all") || lower.contains("except")
                || lower.contains("nothing") || lower.contains("every ") || lower.contains("when ")
                || lower.contains("a person") || lower.contains("offence") || lower.contains("punish")
                || lower.contains("information") || lower.contains("court") || lower.contains("police")
                || lower.contains("magistrate") || lower.contains("property") || lower.contains("shall")
                || lower.matches(".*\\(\\d+\\).*");
    }

    /**
     * Extracts marginal note title from leading or trailing positions, with canonical fallbacks.
     */
    private String resolveTitle(String leadingTitle, String remainder, String secNum, String actCode) {
        if (leadingTitle != null && !leadingTitle.isBlank()) {
            return cleanTitle(leadingTitle);
        }
        if (remainder != null) {
            Matcher trailing = MARGINAL_NOTE_TRAILING.matcher(remainder);
            if (trailing.find()) {
                return cleanTitle(trailing.group(1));
            }
        }
        return getCanonicalTitle(actCode, secNum);
    }

    private String cleanTitle(String raw) {
        return raw.replaceAll("[^a-zA-Z\\s'-]", "").replaceAll("\\s+", " ").trim();
    }

    private static final Map<String, String> CANONICAL_TITLES = Map.ofEntries(
            Map.entry("BNS:3", "General explanations / Common intention"),
            Map.entry("BNS:4", "Punishments"),
            Map.entry("BNS:100", "Culpable homicide"),
            Map.entry("BNS:101", "Murder"),
            Map.entry("BNS:103", "Punishment for murder"),
            Map.entry("BNS:303", "Theft"),
            Map.entry("BNS:304", "Punishment for theft"),
            Map.entry("BNS:309", "Robbery"),
            Map.entry("BNS:310", "Dacoity"),
            Map.entry("BNS:351", "Criminal intimidation"),
            Map.entry("BNSS:173", "Information in cognizable cases"),
            Map.entry("BNSS:174", "Information as to non-cognizable cases"),
            Map.entry("BNSS:175", "Police officer's power to investigate"),
            Map.entry("BNSS:223", "Examination of complainant")
    );

    private String getCanonicalTitle(String actCode, String secNum) {
        return CANONICAL_TITLES.getOrDefault(actCode.toUpperCase() + ":" + secNum, "Section " + secNum);
    }


    // Creates Document chunks with breadcrumb headers for vector indexing
    private List<Document> createChunksForSection(ParsedSection sec) {
        List<Document> docs = new ArrayList<>();
        String fullText = sec.getText().trim();
        List<String> crossRefs = extractCrossReferences(fullText, sec.section);

        // Add Act, Chapter, and Section breadcrumb to improve embedding accuracy
        String contextPrefix = String.format("[%s | %s | Section %s: %s | PDF pages %d-%d]\n",
                sec.actName, sec.chapter, sec.section, sec.title, sec.startPage, sec.endPage);

        if (fullText.length() <= MAX_CHUNK_CHARS) {
            Map<String, Object> metadata = buildMetadata(sec, crossRefs);
            docs.add(new Document(contextPrefix + fullText, metadata));
        } else {
            // Split long sections at subsection boundaries
            List<String> parts = splitSectionParts(fullText, MAX_CHUNK_CHARS);

            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                Map<String, Object> metadata = buildMetadata(sec, crossRefs);
                metadata.put("chunk_index", i);
                metadata.put("total_chunks", parts.size());
                String partHeader = String.format("[%s | %s | Section %s: %s (Part %d of %d) | PDF pages %d-%d]\n",
                        sec.actName, sec.chapter, sec.section, sec.title, i + 1, parts.size(), sec.startPage, sec.endPage);
                docs.add(new Document(partHeader + part, metadata));
            }
        }
        return docs;
    }

    private List<String> splitSectionParts(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("(?m)(?=^\\s*(?:\\(\\d+\\)|Explanation|Exception|Illustration))");

        StringBuilder current = new StringBuilder();
        for (String p : paragraphs) {
            if (p.isBlank()) continue;
            if (p.length() > maxChars) {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                }
                splitLargeText(p, maxChars, result);
            } else if (current.length() + p.length() > maxChars) {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(p).append("\n\n");
            } else {
                current.append(p).append("\n\n");
            }
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        if (result.isEmpty() && !text.isBlank()) {
            splitLargeText(text, maxChars, result);
        }
        return result;
    }

    private void splitLargeText(String text, int maxChars, List<String> result) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int lastBreak = Math.max(text.lastIndexOf("\n", end), text.lastIndexOf(". ", end));
                if (lastBreak > start + (maxChars / 2)) {
                    end = lastBreak + 1;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            start = end;
        }
    }

    private Map<String, Object> buildMetadata(ParsedSection sec, List<String> crossRefs) {
        Map<String, Object> map = new HashMap<>();
        map.put("act_code", sec.actCode);
        map.put("act_name", sec.actName);
        map.put("chapter", sec.chapter);
        map.put("section", sec.section);
        map.put("title", sec.title);
        map.put("source_file", sec.sourceFile);
        map.put("page_start", sec.startPage);
        map.put("page_end", sec.endPage);
        map.put("page_number", sec.startPage);
        if (!crossRefs.isEmpty()) {
            map.put("cross_references", String.join(",", crossRefs));
        }
        return map;
    }

    private List<String> extractCrossReferences(String text, String currentSection) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = CROSS_REFERENCE.matcher(text);
        while (m.find()) {
            String ref = m.group(1);
            if (!ref.equals(currentSection)) {
                refs.add(ref);
            }
        }
        return new ArrayList<>(refs);
    }

    private int getPageNumber(Map<String, Object> metadata, int fallback) {
        if (metadata == null) return fallback;
        Object v = metadata.get("page_number");
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(v.toString()); } catch (Exception ignored) {}
        }
        return fallback;
    }

    /**
     * Internal data structure representing an in-flight parsed legal section.
     */
    private static class ParsedSection {
        final String actCode;
        final String actName;
        final String chapter;
        final String section;
        final String title;
        final String sourceFile;
        final int startPage;
        int endPage;
        final StringBuilder content = new StringBuilder();

        ParsedSection(String actCode, String actName, String chapter, String section, String title, String sourceFile, int page) {
            this.actCode = actCode;
            this.actName = actName;
            this.chapter = chapter;
            this.section = section;
            this.title = title;
            this.sourceFile = sourceFile;
            this.startPage = page;
            this.endPage = page;
        }

        void appendLine(String line) {
            content.append(line).append("\n");
        }

        void updateEndPage(int page) {
            this.endPage = Math.max(this.endPage, page);
        }

        boolean hasContent() {
            return content.length() > 0 && !content.toString().isBlank();
        }

        String getText() {
            return content.toString();
        }
    }
}
