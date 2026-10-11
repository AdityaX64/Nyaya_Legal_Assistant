package com.nyaya.dto;

import java.util.List;

// Response returned after ingesting PDF files
public record IngestResponse(List<String> filenames, int chunksIndexed, int totalChunks) {}


