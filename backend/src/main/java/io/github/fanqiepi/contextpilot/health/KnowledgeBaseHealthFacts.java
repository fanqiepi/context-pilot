package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record KnowledgeBaseHealthFacts(
        OffsetDateTime dataAsOf,
        DocumentStatusCounts documentCounts,
        long issueCount,
        boolean vectorCheckComplete,
        List<KnowledgeBaseHealthDocumentFact> issueCandidates) {

    public KnowledgeBaseHealthFacts {
        Objects.requireNonNull(dataAsOf, "Health data time must not be null");
        Objects.requireNonNull(documentCounts, "Document counts must not be null");
        issueCandidates = List.copyOf(Objects.requireNonNull(issueCandidates, "Issue candidates must not be null"));
        if (issueCount < issueCandidates.size()) {
            throw new IllegalArgumentException("Issue count must not be smaller than returned candidates");
        }
    }
}
