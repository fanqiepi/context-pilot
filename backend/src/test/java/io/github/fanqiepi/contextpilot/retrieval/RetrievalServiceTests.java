package io.github.fanqiepi.contextpilot.retrieval;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.document.DocumentIndexStatusService;
import io.github.fanqiepi.contextpilot.document.DocumentVectorIndex;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTests {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private DocumentVectorIndex documentVectorIndex;

    @Mock
    private DocumentIndexStatusService documentIndexStatusService;

    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        retrievalService = new RetrievalService(
                knowledgeBaseService,
                documentVectorIndex,
                documentIndexStatusService);
    }

    @Test
    void reportsReindexRequirementInsteadOfSearchingIncompatibleVectors() {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(documentVectorIndex.isAvailable()).thenReturn(true);
        when(documentIndexStatusService.requiresReindex(knowledgeBaseId)).thenReturn(true);

        assertThatThrownBy(() -> retrievalService.search(
                knowledgeBaseId,
                new RetrievalSearchRequest("question", 5)))
                .isInstanceOfSatisfying(ConflictException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("KNOWLEDGE_BASE_REINDEX_REQUIRED"));

        verify(documentVectorIndex, never()).search(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
