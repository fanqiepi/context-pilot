package io.github.fanqiepi.contextpilot.research;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchDocumentEligibilityService {

    private final ResearchDocumentMapper documentMapper;
    private final EmbeddingIndexProperties embeddingIndexProperties;

    public ResearchDocumentEligibilityService(
            ResearchDocumentMapper documentMapper,
            EmbeddingIndexProperties embeddingIndexProperties) {
        this.documentMapper = documentMapper;
        this.embeddingIndexProperties = embeddingIndexProperties;
    }

    public List<ResearchDocumentFact> requireEligible(UUID knowledgeBaseId, List<UUID> documentIds) {
        if (documentIds == null || documentIds.size() < 2 || documentIds.size() > 5
                || new HashSet<>(documentIds).size() != documentIds.size()) {
            throw new BadRequestException(
                    "RESEARCH_REQUEST_INVALID",
                    "Research document selection must contain 2 to 5 distinct document IDs");
        }
        String profileId = embeddingIndexProperties.currentProfile().id();
        List<ResearchDocumentFact> facts = documentMapper.selectFacts(documentIds, profileId);
        Map<UUID, ResearchDocumentFact> byId = new LinkedHashMap<>();
        facts.forEach(fact -> byId.put(fact.getId(), fact));
        if (byId.size() != documentIds.size()
                || facts.stream().anyMatch(fact -> !knowledgeBaseId.equals(fact.getKnowledgeBaseId()))) {
            throw new ResourceNotFoundException(
                    "RESEARCH_DOCUMENT_NOT_FOUND",
                    "One or more selected documents were not found in the selected knowledge base");
        }
        Set<UUID> ineligible = new HashSet<>();
        for (ResearchDocumentFact fact : facts) {
            if (fact.getStatus() != DocumentStatus.SUCCEEDED
                    || !profileId.equals(fact.getEmbeddingProfileId())
                    || !fact.isCurrentVectorPresent()) {
                ineligible.add(fact.getId());
            }
        }
        if (!ineligible.isEmpty()) {
            throw new ConflictException(
                    "RESEARCH_DOCUMENT_NOT_ELIGIBLE",
                    "One or more selected documents are not processed with the current embedding profile");
        }
        return documentIds.stream().map(byId::get).toList();
    }
}
