package io.github.fanqiepi.contextpilot.retrieval;

import java.util.UUID;

public record RetrievalResultResponse(
        String chunkId,
        UUID documentId,
        String originalFilename,
        int chunkIndex,
        Integer pageNumber,
        String content,
        Double score) {
}
