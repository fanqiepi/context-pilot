package io.github.fanqiepi.contextpilot.health;

import java.util.Objects;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentProcessingProperties;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseHealthService {

    private final KnowledgeBaseHealthDataPort dataPort;
    private final KnowledgeBaseHealthEvaluator evaluator;
    private final EmbeddingIndexProperties embeddingIndexProperties;
    private final DocumentProcessingProperties documentProcessingProperties;
    private final KnowledgeBaseHealthProperties healthProperties;

    public KnowledgeBaseHealthService(
            KnowledgeBaseHealthDataPort dataPort,
            KnowledgeBaseHealthEvaluator evaluator,
            EmbeddingIndexProperties embeddingIndexProperties,
            DocumentProcessingProperties documentProcessingProperties,
            KnowledgeBaseHealthProperties healthProperties) {
        this.dataPort = dataPort;
        this.evaluator = evaluator;
        this.embeddingIndexProperties = embeddingIndexProperties;
        this.documentProcessingProperties = documentProcessingProperties;
        this.healthProperties = healthProperties;
    }

    public KnowledgeBaseHealthAssessment inspect(UUID knowledgeBaseId) {
        Objects.requireNonNull(knowledgeBaseId, "Knowledge base id must not be null");
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
