package io.github.fanqiepi.contextpilot.evaluation;

public record V3EvaluationConfig(
        String configId,
        String datasetVersion,
        String healthCapabilityVersion,
        String embeddingProfileId,
        int deterministicRepetitions,
        Thresholds thresholds) {

    public record Thresholds(
            double healthIntentAccuracy,
            double healthIntentFalsePositiveRate,
            double healthClassificationAccuracy,
            double completenessAccuracy,
            double repairEligibilityAccuracy,
            double lifecycleSafetyPassRate,
            double historyTraceIntegrityRate) {
    }
}
