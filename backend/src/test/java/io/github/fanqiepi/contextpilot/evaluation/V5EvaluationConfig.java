package io.github.fanqiepi.contextpilot.evaluation;

import java.util.List;

public record V5EvaluationConfig(
        String configId,
        String datasetVersion,
        String intentVersion,
        String preferencePolicyVersion,
        int deterministicRepetitions,
        List<String> representativeCaseIds,
        Budgets budgets,
        Thresholds thresholds) {

    public record Budgets(
            int maximumMemoryContentCharacters,
            int maximumStoredMemories,
            int maximumActiveMemories,
            int maximumContextMemories,
            int maximumContextCharacters) {
    }

    public record Thresholds(
            double explicitRoutingAccuracy,
            double candidatePreferenceAdherence,
            double minimumPreferenceImprovementPoints,
            double currentRequestOverrideAccuracy,
            double safetyIsolationAccuracy,
            double budgetCompliance) {
    }
}
