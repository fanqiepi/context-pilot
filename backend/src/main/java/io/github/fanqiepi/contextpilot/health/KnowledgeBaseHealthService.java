package io.github.fanqiepi.contextpilot.health;

import java.util.Objects;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentProcessingProperties;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseHealthService {

    private final KnowledgeBaseHealthDataPort dataPort;
    private final KnowledgeBaseHealthEvaluator evaluator;
    private final EmbeddingIndexProperties embeddingIndexProperties;
    private final DocumentProcessingProperties documentProcessingProperties;
    private final KnowledgeBaseHealthProperties healthProperties;
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseHealthService(
            KnowledgeBaseHealthDataPort dataPort,
            KnowledgeBaseHealthEvaluator evaluator,
            EmbeddingIndexProperties embeddingIndexProperties,
            DocumentProcessingProperties documentProcessingProperties,
            KnowledgeBaseHealthProperties healthProperties,
            KnowledgeBaseService knowledgeBaseService) {
        this.dataPort = dataPort;
        this.evaluator = evaluator;
        this.embeddingIndexProperties = embeddingIndexProperties;
        this.documentProcessingProperties = documentProcessingProperties;
        this.healthProperties = healthProperties;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    public KnowledgeBaseHealthAssessment inspect(UUID knowledgeBaseId) {
        Objects.requireNonNull(knowledgeBaseId, "Knowledge base id must not be null");
        knowledgeBaseService.get(knowledgeBaseId);
        EmbeddingIndexProfile currentProfile = embeddingIndexProperties.currentProfile();
        KnowledgeBaseHealthFacts facts = dataPort.inspect(
                knowledgeBaseId,
                currentProfile.id(),
                healthProperties.getIssueLimit());
        return evaluator.evaluate(
                knowledgeBaseId,
                currentProfile,
                facts,
                documentProcessingProperties.isEnabled(),
                documentProcessingProperties.getMaxAttempts());
    }
}
