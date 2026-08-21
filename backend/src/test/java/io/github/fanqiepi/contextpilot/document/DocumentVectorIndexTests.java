package io.github.fanqiepi.contextpilot.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.processing.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DocumentVectorIndexTests {

    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Mock
    private VectorStore vectorStore;

    private DocumentVectorIndex documentVectorIndex;
    private EmbeddingIndexProfile currentProfile;

    @BeforeEach
    void setUp() {
        EmbeddingIndexProperties properties = new EmbeddingIndexProperties();
        currentProfile = properties.currentProfile();
        documentVectorIndex = new DocumentVectorIndex(vectorStoreProvider, properties);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
    }

    @Test
    void writesEmbeddingProfileIntoEveryVectorChunk(CapturedOutput output) {
        SourceDocumentEntity source = sourceDocument();
        DocumentChunk chunk = new DocumentChunk(0, "knowledge content", Map.of("page_number", 1));

        documentVectorIndex.replace(source, List.of(chunk));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        Map<String, Object> metadata = documents.getValue().getFirst().getMetadata();
        assertThat(metadata.get("embedding_profile_id")).isEqualTo(currentProfile.id());
        assertThat(metadata.get("embedding_model")).isEqualTo(currentProfile.model());
        assertThat(metadata.get("embedding_dimensions")).isEqualTo(currentProfile.dimensions());
        assertThat(output)
                .contains("ai.call.started operation=EMBEDDING_INDEX")
                .contains("provider=" + currentProfile.provider())
                .contains("model=" + currentProfile.model())
                .contains("resourceType=sourceDocument resourceId=" + source.getId())
                .contains("ai.call.succeeded operation=EMBEDDING_INDEX")
                .doesNotContain("knowledge content");
    }

    @Test
    void isolatesSearchByKnowledgeBaseAndEmbeddingProfile(CapturedOutput output) {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        documentVectorIndex.search(knowledgeBaseId, "query", 5);

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getFilterExpression().toString())
                .contains(knowledgeBaseId.toString())
                .contains("embedding_profile_id")
                .contains(currentProfile.id());
        assertThat(output)
                .doesNotContain("ai.call.started operation=EMBEDDING_RETRIEVAL")
                .doesNotContain("ai.call.succeeded operation=EMBEDDING_RETRIEVAL")
                .doesNotContain("query");
    }

    private SourceDocumentEntity sourceDocument() {
        SourceDocumentEntity source = new SourceDocumentEntity();
        source.setId(UUID.randomUUID());
        source.setKnowledgeBaseId(UUID.randomUUID());
        source.setOriginalFilename("notes.txt");
        return source;
    }
}
