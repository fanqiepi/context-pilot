package io.github.fanqiepi.contextpilot.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class V4ResearchEvaluationHarness {

    enum Strategy {
        SINGLE_TURN_RAG,
        BOUNDED_PLAN_AND_EXECUTE
    }

    private final V4EvaluationDataset dataset;
    private final V4EvaluationConfig config;
    private final Map<String, V4EvaluationAssets.CorpusDocument> corpus;

    V4ResearchEvaluationHarness(V4EvaluationDataset dataset, V4EvaluationConfig config) {
        this.dataset = dataset;
        this.config = config;
        this.corpus = V4EvaluationAssets.corpus(dataset);
    }

    EvaluationResult evaluate(V4EvaluationDataset.SemanticCase testCase, Strategy strategy) {
        if (!testCase.explicitResearch()) {
            return EvaluationResult.notTriggered(testCase.id(), strategy);
        }
        validateSelection(testCase.selectedDocumentIds());
        List<PlanStep> plan = strategy == Strategy.SINGLE_TURN_RAG
                ? List.of(new PlanStep("single-turn", testCase.question(), testCase.selectedDocumentIds()))
                : plan(testCase);
        boolean schemaValid = validatePlan(plan, testCase.selectedDocumentIds());
        if (!schemaValid) {
            return EvaluationResult.invalidPlan(testCase.id(), strategy, plan);
        }

        Execution execution = strategy == Strategy.SINGLE_TURN_RAG
                ? executeSingleTurn(testCase)
                : executeBounded(plan);
        Set<String> expectedEvidence = expectedEvidence(testCase);
        Set<String> actualEvidence = execution.evidence().keySet();
        Set<String> supportingCitations = new LinkedHashSet<>(actualEvidence);
        supportingCitations.retainAll(expectedEvidence);
        List<String> missingStatements = testCase.expectedMissingEvidence().stream()
                .filter(key -> !actualEvidence.contains(key))
                .toList();

        String disposition = supportingCitations.isEmpty() ? "REFUSED" : "ANSWERED";
        boolean dispositionCorrect = disposition.equals(testCase.expectedDisposition());
        boolean taskComplete = dispositionCorrect && switch (disposition) {
            case "ANSWERED" -> supportingCitations.containsAll(expectedEvidence)
                    && missingStatements.containsAll(testCase.expectedMissingEvidence());
            case "REFUSED" -> actualEvidence.isEmpty();
            default -> false;
        };
        double dimensionCoverage = dimensionCoverage(testCase, supportingCitations);
        double citationCorrectness = supportingCitations.isEmpty()
                ? 1.0
                : supportingCitations.stream().filter(expectedEvidence::contains).count()
                        / (double) supportingCitations.size();
        double citationCoverage = expectedEvidence.isEmpty()
                ? 1.0
                : supportingCitations.size() / (double) expectedEvidence.size();
        boolean rangeIsolated = execution.evidence().values().stream()
                .allMatch(chunk -> testCase.selectedDocumentIds().contains(chunk.documentId())
                        && corpus.get(chunk.documentId()).knowledgeBaseId().equals("KB-MAIN"));
        Usage usage = usage(strategy, plan, execution);
        boolean withinBudget = withinBudget(usage);

        return new EvaluationResult(
                testCase.id(),
                strategy,
                disposition,
                taskComplete,
                schemaValid,
                dispositionCorrect,
                dimensionCoverage,
                citationCorrectness,
                citationCoverage,
                citationCorrectness,
                rangeIsolated,
                withinBudget,
                List.copyOf(plan),
                Set.copyOf(supportingCitations),
                missingStatements,
                usage);
    }

    private List<PlanStep> plan(V4EvaluationDataset.SemanticCase testCase) {
        List<String> dimensions = detectDimensions(testCase.question());
        if (dimensions.isEmpty()) {
            return List.of(new PlanStep(
                    "检查所选文档是否提供问题所需证据",
                    testCase.question(),
                    testCase.selectedDocumentIds()));
        }
        return dimensions.stream()
                .map(dimension -> new PlanStep(
                        "比较所选文档的" + dimension,
                        dimension,
                        testCase.selectedDocumentIds()))
                .toList();
    }

    private Execution executeSingleTurn(V4EvaluationDataset.SemanticCase testCase) {
        List<ScoredChunk> hits = retrieve(testCase.question(), testCase.selectedDocumentIds());
        int limit = Math.min(config.budgets().singleTurnTopK(), hits.size());
        Map<String, V4EvaluationAssets.CorpusChunk> evidence = new LinkedHashMap<>();
        int characters = 0;
        for (ScoredChunk hit : hits.subList(0, limit)) {
            int excerptLength = Math.min(
                    hit.chunk().content().length(), config.budgets().maximumExcerptCharacters());
            if (!evidence.isEmpty()
                    && characters + excerptLength > config.budgets().maximumEvidenceCharacters()) {
                break;
            }
            evidence.put(hit.chunk().id(), hit.chunk());
            characters += excerptLength;
        }
        return new Execution(evidence, 1, limit, characters);
    }

    private Execution executeBounded(List<PlanStep> plan) {
        List<StepDocumentHits> allHits = new ArrayList<>();
        int rawHits = 0;
        for (PlanStep step : plan) {
            for (String documentId : step.documentIds()) {
                List<ScoredChunk> hits = retrieve(step.query(), List.of(documentId));
                int limit = Math.min(config.budgets().perDocumentTopK(), hits.size());
                List<ScoredChunk> limited = hits.subList(0, limit);
                allHits.add(new StepDocumentHits(step, documentId, List.copyOf(limited)));
                rawHits += limited.size();
            }
        }

        Map<String, V4EvaluationAssets.CorpusChunk> evidence = new LinkedHashMap<>();
        int characters = 0;
        for (StepDocumentHits group : allHits) {
            if (!group.hits().isEmpty()) {
                characters = addEvidence(evidence, group.hits().getFirst().chunk(), characters);
            }
        }
        outer:
        for (StepDocumentHits group : allHits) {
            for (int index = 1; index < group.hits().size(); index++) {
                int next = addEvidence(evidence, group.hits().get(index).chunk(), characters);
                if (next == characters && evidence.size() >= config.budgets().maximumEvidenceChunks()) {
                    break outer;
                }
                characters = next;
            }
        }
        return new Execution(evidence, allHits.size(), rawHits, characters);
    }

    private int addEvidence(
            Map<String, V4EvaluationAssets.CorpusChunk> evidence,
            V4EvaluationAssets.CorpusChunk chunk,
            int characters) {
        if (evidence.containsKey(chunk.id())) {
            return characters;
        }
        int excerptLength = Math.min(chunk.content().length(), config.budgets().maximumExcerptCharacters());
        if (evidence.size() >= config.budgets().maximumEvidenceChunks()
                || characters + excerptLength > config.budgets().maximumEvidenceCharacters()) {
            return characters;
        }
        evidence.put(chunk.id(), chunk);
        return characters + excerptLength;
    }

    private List<ScoredChunk> retrieve(String query, List<String> documentIds) {
        List<String> queryDimensions = detectDimensions(query);
        if (queryDimensions.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> hits = new ArrayList<>();
        for (String documentId : documentIds) {
            V4EvaluationAssets.CorpusDocument document = corpus.get(documentId);
            for (V4EvaluationAssets.CorpusChunk chunk : document.chunks()) {
                int ordinal = queryDimensions.indexOf(chunk.dimension());
                if (ordinal >= 0) {
                    hits.add(new ScoredChunk(chunk, 1.0 - (ordinal * 0.01)));
                }
            }
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(hit -> hit.chunk().documentId())
                        .thenComparing(hit -> hit.chunk().id()))
                .toList();
    }

    private List<String> detectDimensions(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<DetectedDimension> detected = new ArrayList<>();
        for (V4EvaluationConfig.DimensionDefinition definition : config.dimensions()) {
            int position = position(normalized, definition);
            if (position >= 0) {
                detected.add(new DetectedDimension(definition.id(), position));
            }
        }
        return detected.stream()
                .sorted(Comparator.comparingInt(DetectedDimension::position))
                .map(DetectedDimension::id)
                .toList();
    }

    private int position(String normalized, V4EvaluationConfig.DimensionDefinition definition) {
        int position = normalized.indexOf(definition.id().toLowerCase(Locale.ROOT));
        for (String alias : definition.aliases()) {
            int aliasPosition = normalized.indexOf(alias.toLowerCase(Locale.ROOT));
            if (aliasPosition >= 0 && (position < 0 || aliasPosition < position)) {
                position = aliasPosition;
            }
        }
        return position;
    }

    private boolean validatePlan(List<PlanStep> plan, List<String> selectedDocumentIds) {
        if (plan.isEmpty() || plan.size() > config.budgets().maximumPlanSteps()) {
            return false;
        }
        Set<String> selected = Set.copyOf(selectedDocumentIds);
        Set<String> covered = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        int retrievalCalls = 0;
        for (PlanStep step : plan) {
            if (step.goal() == null || step.goal().isBlank()
                    || step.query() == null || step.query().isBlank()
                    || step.documentIds() == null || step.documentIds().isEmpty()
                    || !selected.containsAll(step.documentIds())) {
                return false;
            }
            List<String> sortedDocuments = step.documentIds().stream().sorted().toList();
            String signature = step.query().strip().toLowerCase(Locale.ROOT) + "|" + sortedDocuments;
            if (!signatures.add(signature)) {
                return false;
            }
            covered.addAll(step.documentIds());
            retrievalCalls += step.documentIds().size();
        }
        return covered.equals(selected) && retrievalCalls <= config.budgets().maximumRetrievalCalls();
    }

    private void validateSelection(List<String> selectedDocumentIds) {
        if (selectedDocumentIds.size() < config.budgets().minimumDocuments()
                || selectedDocumentIds.size() > config.budgets().maximumDocuments()
                || selectedDocumentIds.stream().distinct().count() != selectedDocumentIds.size()
                || !corpus.keySet().containsAll(selectedDocumentIds)) {
            throw new IllegalArgumentException("Invalid V4 evaluation document selection");
        }
        Set<String> knowledgeBases = selectedDocumentIds.stream()
                .map(corpus::get)
                .map(V4EvaluationAssets.CorpusDocument::knowledgeBaseId)
                .collect(java.util.stream.Collectors.toSet());
        if (!knowledgeBases.equals(Set.of("KB-MAIN"))) {
            throw new IllegalArgumentException("Selected documents must belong to KB-MAIN");
        }
    }

    private Set<String> expectedEvidence(V4EvaluationDataset.SemanticCase testCase) {
        if (!"ANSWERED".equals(testCase.expectedDisposition())) {
            return Set.of();
        }
        Set<String> missing = Set.copyOf(testCase.expectedMissingEvidence());
        Set<String> expected = new LinkedHashSet<>();
        for (String dimension : testCase.expectedDimensions()) {
            for (String documentId : testCase.selectedDocumentIds()) {
                String key = documentId + ":" + dimension;
                if (!missing.contains(key)) {
                    expected.add(key);
                }
            }
        }
        return Set.copyOf(expected);
    }

    private double dimensionCoverage(
            V4EvaluationDataset.SemanticCase testCase,
            Set<String> supportingCitations) {
        if (testCase.expectedDimensions().isEmpty()
                || !"ANSWERED".equals(testCase.expectedDisposition())) {
            return 1.0;
        }
        Set<String> missing = Set.copyOf(testCase.expectedMissingEvidence());
        long covered = testCase.expectedDimensions().stream().filter(dimension ->
                testCase.selectedDocumentIds().stream()
                        .map(documentId -> documentId + ":" + dimension)
                        .filter(key -> !missing.contains(key))
                        .allMatch(supportingCitations::contains)).count();
        return covered / (double) testCase.expectedDimensions().size();
    }

    private Usage usage(Strategy strategy, List<PlanStep> plan, Execution execution) {
        int modelCalls = strategy == Strategy.SINGLE_TURN_RAG ? 1 : 2;
        int elapsedMillis = 20 + execution.retrievalCalls() * 3 + modelCalls * 5;
        return new Usage(
                modelCalls,
                plan.size(),
                execution.retrievalCalls(),
                execution.rawHits(),
                execution.evidence().size(),
                execution.evidenceCharacters(),
                elapsedMillis);
    }

    private boolean withinBudget(Usage usage) {
        V4EvaluationConfig.Budgets budgets = config.budgets();
        return usage.modelCalls() <= budgets.maximumModelCalls()
                && usage.planSteps() <= budgets.maximumPlanSteps()
                && usage.retrievalCalls() <= budgets.maximumRetrievalCalls()
                && usage.rawHits() <= budgets.maximumRawHits()
                && usage.evidenceChunks() <= budgets.maximumEvidenceChunks()
                && usage.evidenceCharacters() <= budgets.maximumEvidenceCharacters()
                && usage.elapsedMillis() <= budgets.hardTimeoutMillis();
    }

    record PlanStep(String goal, String query, List<String> documentIds) {
        PlanStep {
            documentIds = List.copyOf(documentIds);
        }
    }

    record Usage(
            int modelCalls,
            int planSteps,
            int retrievalCalls,
            int rawHits,
            int evidenceChunks,
            int evidenceCharacters,
            int elapsedMillis) {
    }

    record EvaluationResult(
            String caseId,
            Strategy strategy,
            String disposition,
            boolean taskComplete,
            boolean planSchemaValid,
            boolean dispositionCorrect,
            double dimensionCoverage,
            double citationCorrectness,
            double citationCoverage,
            double answerFaithfulness,
            boolean rangeIsolated,
            boolean withinBudget,
            List<PlanStep> plan,
            Set<String> citations,
            List<String> missingStatements,
            Usage usage) {

        static EvaluationResult notTriggered(String caseId, Strategy strategy) {
            return new EvaluationResult(
                    caseId, strategy, "NOT_TRIGGERED", true, true, true,
                    1.0, 1.0, 1.0, 1.0, true, true,
                    List.of(), Set.of(), List.of(), new Usage(0, 0, 0, 0, 0, 0, 0));
        }

        static EvaluationResult invalidPlan(String caseId, Strategy strategy, List<PlanStep> plan) {
            return new EvaluationResult(
                    caseId, strategy, "FAILED", false, false, false,
                    0.0, 0.0, 0.0, 0.0, true, true,
                    List.copyOf(plan), Set.of(), List.of(), new Usage(1, plan.size(), 0, 0, 0, 0, 0));
        }
    }

    private record DetectedDimension(String id, int position) {
    }

    private record ScoredChunk(V4EvaluationAssets.CorpusChunk chunk, double score) {
    }

    private record StepDocumentHits(PlanStep step, String documentId, List<ScoredChunk> hits) {
    }

    private record Execution(
            Map<String, V4EvaluationAssets.CorpusChunk> evidence,
            int retrievalCalls,
            int rawHits,
            int evidenceCharacters) {
    }
}
