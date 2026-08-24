package io.github.fanqiepi.contextpilot.evaluation;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record V5EvaluationDataset(
        String datasetId,
        String version,
        String description,
        List<DocumentFixture> documents,
        List<RoutingCase> routingCases,
        List<PreferenceCase> preferenceCases,
        List<SafetyCase> safetyCases,
        List<LifecycleCoverageCase> lifecycleCoverage) {

    public record DocumentFixture(
            String id,
            String knowledgeBaseId,
            String filename,
            String corpusPath) {
    }

    public record RoutingCase(
            String id,
            JsonNode input,
            String expectedDecision,
            String expectedContent,
            List<String> tags) {

        public String materializedInput() {
            if (input.isTextual()) {
                return input.textValue();
            }
            String value = input.path("value").asText();
            int repeat = input.path("repeat").asInt(1);
            return value.repeat(repeat);
        }
    }

    public record PreferenceCase(
            String id,
            String memoryContent,
            String memoryMode,
            String currentRequest,
            String expectedStyle,
            boolean overrideExpected,
            List<String> tags) {
    }

    public record SafetyCase(
            String id,
            String scenario,
            List<MemoryFixture> memoryFixtures,
            String question,
            String expectedRoute,
            boolean evidenceAvailable,
            String expectedDisposition,
            List<String> expectedCitationIds,
            int expectedSelectedCount,
            boolean expectTruncated,
            List<String> tags) {
    }

    public record MemoryFixture(
            String id,
            int revision,
            String content,
            int repeat) {

        public String materializedContent() {
            return content.repeat(repeat);
        }
    }

    public record LifecycleCoverageCase(
            String id,
            String scenario,
            int targetSlice,
            String expectedBehavior,
            List<String> tags) {
    }
}
