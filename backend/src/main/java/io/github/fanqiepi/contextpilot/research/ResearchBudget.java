package io.github.fanqiepi.contextpilot.research;

public record ResearchBudget(
        int maximumPlanSteps,
        int maximumRetrievalCalls,
        int perDocumentTopK,
        int maximumRawHits,
        int maximumEvidenceChunks,
        int maximumEvidenceCharacters,
        int maximumExcerptCharacters,
        int maximumStepEvidenceCharacters,
        long hardTimeoutMillis) {

    public static final ResearchBudget V1 = new ResearchBudget(4, 20, 3, 60, 24, 24000, 1000, 8000, 90000);
    public static final int ANSWER_MAX_CHARACTERS = 1800;
}
