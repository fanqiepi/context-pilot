package io.github.fanqiepi.contextpilot.evaluation;

import java.util.List;

public record V4EvaluationConfig(
        String configId,
        String datasetVersion,
        String planVersion,
        int deterministicRepetitions,
        List<String> representativeCaseIds,
        List<DimensionDefinition> dimensions,
        Budgets budgets,
        Thresholds thresholds) {

    public record DimensionDefinition(String id, List<String> aliases) {
    }

    public record Budgets(
            int minimumDocuments,
            int maximumDocuments,
            int singleTurnTopK,
            int maximumPlanSteps,
            int maximumRetrievalCalls,
            int perDocumentTopK,
            int maximumRawHits,
            int maximumEvidenceChunks,
            int maximumEvidenceCharacters,
            int maximumExcerptCharacters,
            int maximumStepEvidenceCharacters,
            int maximumModelCalls,
            int hardTimeoutMillis) {
    }

    public record Thresholds(
            double explicitEntryAccuracy,
            double selectedDocumentCoverage,
            double planSchemaValidity,
            double multiStepTaskCompletion,
            double multiStepImprovementPoints,
            double comparisonDimensionCoverage,
            double citationCorrectness,
            double citationCoverage,
            double answerFaithfulness,
            double missingAndRefusalCorrectness,
            double maximumControlQualityDropPoints,
            double rangeIsolation,
            double budgetCompliance) {
    }
}
