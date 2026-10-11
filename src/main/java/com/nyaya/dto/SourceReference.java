package com.nyaya.dto;

// Provenance details for retrieved legal sections (powers UI statute drawer)
public record SourceReference(
        String actCode,     // BNS or BNSS
        String source,      // file name
        String section,     // section number (e.g. "100")
        String title,       // section title (e.g. "Murder")
        int pageStart,
        int pageEnd,
        String excerpt      // verbatim section text
) {}


