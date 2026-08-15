package io.github.fanqiepi.contextpilot.evaluation;

public record V2EvaluationConfig(
        String configId,
        String datasetVersion,
        String capabilityVersion,
        String actionType,
        int deterministicRepetitions,
        Thresholds thresholds) {

    public record Thresholds(
            double routingAccuracy,
            double businessActionFalsePositiveRate,
            double parameterValidationPassRate,
            double actionSafetyPassRate) {
    }
}
