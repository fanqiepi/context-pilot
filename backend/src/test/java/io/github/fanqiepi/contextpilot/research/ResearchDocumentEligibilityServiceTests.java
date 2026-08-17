package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResearchDocumentEligibilityServiceTests {
    @Mock private ResearchDocumentMapper mapper;
    private ResearchDocumentEligibilityService service;

    @BeforeEach
    void setUp() {
        service = new ResearchDocumentEligibilityService(mapper, new EmbeddingIndexProperties());
    }

    @Test
    void acceptsSucceededDocumentsWithCurrentVectorsAndPreservesSelectionOrder() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(mapper.selectFacts(List.of(second, first), "dashscope_qwen3_7_1024_v1"))
                .thenReturn(List.of(fact(first, knowledgeBaseId, true), fact(second, knowledgeBaseId, true)));

        assertThat(service.requireEligible(knowledgeBaseId, List.of(second, first)))
                .extracting(ResearchDocumentFact::getId)
                .containsExactly(second, first);
    }

    @Test
    void hidesMissingOrCrossKnowledgeBaseDocumentsBehindNotFound() {
        UUID knowledgeBaseId = UUID.randomUUID();
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(mapper.selectFacts(ids, "dashscope_qwen3_7_1024_v1"))
                .thenReturn(List.of(fact(ids.getFirst(), knowledgeBaseId, true)));

        assertThatThrownBy(() -> service.requireEligible(knowledgeBaseId, ids))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code").isEqualTo("RESEARCH_DOCUMENT_NOT_FOUND");
    }

    @Test
    void rejectsDocumentsWithoutCurrentVectorEvidence() {
        UUID knowledgeBaseId = UUID.randomUUID();
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(mapper.selectFacts(ids, "dashscope_qwen3_7_1024_v1"))
                .thenReturn(List.of(
                        fact(ids.getFirst(), knowledgeBaseId, true),
                        fact(ids.getLast(), knowledgeBaseId, false)));

        assertThatThrownBy(() -> service.requireEligible(knowledgeBaseId, ids))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("RESEARCH_DOCUMENT_NOT_ELIGIBLE");
    }

    private ResearchDocumentFact fact(UUID id, UUID knowledgeBaseId, boolean vectorPresent) {
        ResearchDocumentFact fact = new ResearchDocumentFact();
        fact.setId(id);
        fact.setKnowledgeBaseId(knowledgeBaseId);
        fact.setOriginalFilename(id + ".md");
        fact.setStatus(DocumentStatus.SUCCEEDED);
        fact.setEmbeddingProfileId("dashscope_qwen3_7_1024_v1");
        fact.setCurrentVectorPresent(vectorPresent);
        return fact;
    }
}
