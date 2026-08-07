package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;

public record ChatCitationResponse(
        int rank,
        String chunkId,
        UUID documentId,
        String originalFilename,
        int chunkIndex,
        Integer pageNumber,
        Double score,
        String excerpt) {

    private static final int MAX_EXCERPT_CHARACTERS = 2000;

    static ChatCitationResponse from(int rank, RetrievalResultResponse result) {
        return new ChatCitationResponse(
                rank,
                result.chunkId(),
                result.documentId(),
                result.originalFilename(),
                result.chunkIndex(),
                result.pageNumber(),
                result.score(),
                excerpt(result.content()));
    }

    private static String excerpt(String content) {
        if (content == null || content.length() <= MAX_EXCERPT_CHARACTERS) {
            return content;
        }
        return content.substring(0, MAX_EXCERPT_CHARACTERS);
    }
}
