package io.github.fanqiepi.contextpilot.evaluation;

import java.util.List;

public record V4EvaluationDataset(
        String datasetId,
        String version,
        String description,
        List<DocumentFixture> documents,
        List<SemanticCase> semanticCases,
        List<LifecycleCoverageCase> lifecycleCoverage) {

    public record DocumentFixture(
            String id,
            String knowledgeBaseId,
            String filename,
            String corpusPath) {
    }

    public record SemanticCase(
            String id,
            String category,
            boolean explicitResearch,
            List<String> selectedDocumentIds,
            String question,
            List<String> expectedDimensions,
            List<String> expectedMissingEvidence,
            String expectedDisposition,
            List<String> tags) {
    }

    public record LifecycleCoverageCase(
            String id,
            String scenario,
            int targetSlice,
            String expectedBehavior,
            List<String> tags) {
    }
}
