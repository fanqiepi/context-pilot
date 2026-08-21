package io.github.fanqiepi.contextpilot.retrieval;

import java.util.Map;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.document.DocumentIndexStatusService;
import io.github.fanqiepi.contextpilot.document.DocumentVectorIndex;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentVectorIndex documentVectorIndex;
    private final DocumentIndexStatusService documentIndexStatusService;

    public RetrievalService(
            KnowledgeBaseService knowledgeBaseService,
            DocumentVectorIndex documentVectorIndex,
            DocumentIndexStatusService documentIndexStatusService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentVectorIndex = documentVectorIndex;
        this.documentIndexStatusService = documentIndexStatusService;
    }

    public java.util.List<RetrievalResultResponse> search(
            UUID knowledgeBaseId,
            RetrievalSearchRequest request) {
        return search(knowledgeBaseId, request, null);
    }

    public java.util.List<RetrievalResultResponse> search(
            UUID knowledgeBaseId,
            RetrievalSearchRequest request,
            String traceId) {
        knowledgeBaseService.get(knowledgeBaseId);
        if (!documentVectorIndex.isAvailable()) {
            throw unavailable();
        }
        if (documentIndexStatusService.requiresReindex(knowledgeBaseId)) {
            throw new ConflictException(
                    "KNOWLEDGE_BASE_REINDEX_REQUIRED",
                    "Knowledge base documents must be reindexed with the current embedding profile");
        }
        try {
            return documentVectorIndex.search(
                            knowledgeBaseId,
                            request.query().strip(),
                            request.effectiveTopK(),
                            traceId)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        } catch (RuntimeException exception) {
            if (exception instanceof InternalServiceException internal) {
                throw internal;
            }
            throw new InternalServiceException(
                    "RETRIEVAL_FAILED",
                    "Knowledge base retrieval failed",
                    exception);
        }
    }

    public java.util.List<RetrievalResultResponse> searchDocument(
            UUID knowledgeBaseId,
            UUID documentId,
            String query,
            int topK) {
        return searchDocument(knowledgeBaseId, documentId, query, topK, null);
    }

    public java.util.List<RetrievalResultResponse> searchDocument(
            UUID knowledgeBaseId,
            UUID documentId,
            String query,
            int topK,
            String traceId) {
        if (!documentVectorIndex.isAvailable()) {
            throw unavailable();
        }
        try {
            return documentVectorIndex.search(knowledgeBaseId, documentId, query.strip(), topK, traceId)
                    .stream()
                    .map(this::toResponse)
                    .filter(result -> result.documentId().equals(documentId))
                    .toList();
        } catch (RuntimeException exception) {
            if (exception instanceof InternalServiceException internal) {
                throw internal;
            }
            throw new InternalServiceException(
                    "RESEARCH_DOCUMENT_RETRIEVAL_FAILED",
                    "Document-scoped retrieval failed",
                    exception);
        }
    }

    private RetrievalResultResponse toResponse(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RetrievalResultResponse(
                document.getId(),
                UUID.fromString(metadata.get("document_id").toString()),
                metadata.get("original_filename").toString(),
                number(metadata.get("chunk_index"), 0),
                pageNumber(metadata),
                document.getText(),
                document.getScore());
    }

    private Integer pageNumber(Map<String, Object> metadata) {
        Object value = metadata.get("page_number");
        if (value == null) {
            value = metadata.get("start_page_number");
        }
        return value == null ? null : number(value, 0);
    }

    private int number(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? defaultValue : Integer.parseInt(value.toString());
    }

    private InternalServiceException unavailable() {
        return new InternalServiceException(
                "VECTOR_STORE_UNAVAILABLE",
                "Vector retrieval is not enabled",
                new IllegalStateException("VectorStore bean is not available"));
    }
}
