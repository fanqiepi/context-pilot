package io.github.fanqiepi.contextpilot.document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.processing.DocumentChunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class DocumentVectorIndex {

    private static final String KNOWLEDGE_BASE_ID = "knowledge_base_id";
    private static final String DOCUMENT_ID = "document_id";
    private static final String ORIGINAL_FILENAME = "original_filename";

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public DocumentVectorIndex(ObjectProvider<VectorStore> vectorStoreProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
    }

    public boolean isAvailable() {
        return vectorStoreProvider.getIfAvailable() != null;
    }

    public void replace(SourceDocumentEntity source, List<DocumentChunk> chunks) {
        VectorStore vectorStore = requireVectorStore();
        String filter = documentFilter(source.getId());
        vectorStore.delete(filter);
        try {
            vectorStore.add(toDocuments(source, chunks));
        } catch (RuntimeException exception) {
            try {
                vectorStore.delete(filter);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public void deleteByDocumentId(UUID documentId) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore != null) {
            vectorStore.delete(documentFilter(documentId));
        }
    }

    public List<Document> search(UUID knowledgeBaseId, String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThresholdAll()
                .filterExpression(KNOWLEDGE_BASE_ID + " == '" + knowledgeBaseId + "'")
                .build();
        return requireVectorStore().similaritySearch(request);
    }

    private List<Document> toDocuments(SourceDocumentEntity source, List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("Document chunks must not be empty");
        }
        List<Document> documents = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            Map<String, Object> metadata = sanitizeMetadata(chunk.metadata());
            metadata.put(KNOWLEDGE_BASE_ID, source.getKnowledgeBaseId().toString());
            metadata.put(DOCUMENT_ID, source.getId().toString());
            metadata.put(ORIGINAL_FILENAME, source.getOriginalFilename());
            metadata.put("chunk_index", chunk.index());
            documents.add(new Document(chunkId(source.getId(), chunk.index()), chunk.text(), metadata));
        }
        return documents;
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                metadata.put(key, value);
            } else if (value != null) {
                metadata.put(key, value.toString());
            }
        });
        return metadata;
    }

    private String chunkId(UUID documentId, int chunkIndex) {
        return UUID.nameUUIDFromBytes(
                (documentId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String documentFilter(UUID documentId) {
        return DOCUMENT_ID + " == '" + documentId + "'";
    }

    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("Vector store is not available");
        }
        return vectorStore;
    }
}
