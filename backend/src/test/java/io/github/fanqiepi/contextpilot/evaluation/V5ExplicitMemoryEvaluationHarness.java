package io.github.fanqiepi.contextpilot.evaluation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class V5ExplicitMemoryEvaluationHarness {

    private static final List<String> EXPLICIT_PREFIXES = List.of(
            "请记住我的偏好：",
            "请记住我的偏好:",
            "记住回答偏好：",
            "记住回答偏好:");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ADDITIONAL_INSTRUCTION = Pattern.compile(
            "(?:；|;)\\s*然后|然后(?:删除|执行|调用)|并执行");

    private final V5EvaluationConfig config;

    V5ExplicitMemoryEvaluationHarness(V5EvaluationConfig config) {
        this.config = config;
    }

    RoutingResult evaluateRouting(V5EvaluationDataset.RoutingCase testCase) {
        String normalized = normalize(testCase.materializedInput());
        String prefix = EXPLICIT_PREFIXES.stream()
                .filter(normalized::startsWith)
                .findFirst()
                .orElse(null);
        if (prefix == null) {
            return new RoutingResult("NOT_TRIGGERED", null);
        }
        String content = normalize(normalized.substring(prefix.length()));
        if (content.isEmpty()
                || content.length() > config.budgets().maximumMemoryContentCharacters()
                || ADDITIONAL_INSTRUCTION.matcher(content).find()) {
            return new RoutingResult("INVALID", null);
        }
        return new RoutingResult("PROPOSE", content);
    }

    PreferenceResult evaluatePreference(
            V5EvaluationDataset.PreferenceCase testCase,
            PreferenceStrategy strategy) {
        String currentOverride = currentRequestOverride(testCase.currentRequest());
        String actualStyle;
        if (currentOverride != null) {
            actualStyle = currentOverride;
        } else if (strategy == PreferenceStrategy.WITH_BOUNDED_MEMORY
                && testCase.memoryMode().equals("USE")) {
            actualStyle = memoryStyle(testCase.memoryContent());
        } else {
            actualStyle = defaultStyle(testCase.memoryContent());
        }
        return new PreferenceResult(
                actualStyle,
                actualStyle.equals(testCase.expectedStyle()),
                !testCase.overrideExpected()
                        && strategy == PreferenceStrategy.WITH_BOUNDED_MEMORY
                        && testCase.memoryMode().equals("USE"),
                !testCase.overrideExpected() || actualStyle.equals(testCase.expectedStyle()));
    }

    SafetyResult evaluateSafety(V5EvaluationDataset.SafetyCase testCase) {
        List<SelectedMemory> selected = new ArrayList<>();
        int characters = 0;
        for (V5EvaluationDataset.MemoryFixture fixture : testCase.memoryFixtures()) {
            String content = fixture.materializedContent();
            if (selected.size() >= config.budgets().maximumContextMemories()
                    || characters + content.length() > config.budgets().maximumContextCharacters()) {
                break;
            }
            selected.add(new SelectedMemory(fixture.id(), fixture.revision(), content.length()));
            characters += content.length();
        }
        boolean truncated = selected.size() < testCase.memoryFixtures().size();
        String disposition = testCase.evidenceAvailable() ? "ANSWERED" : "REFUSED";
        List<String> citationIds = testCase.evidenceAvailable() ? List.of("DOC-A#1") : List.of();
        boolean withinBudget = selected.size() <= config.budgets().maximumContextMemories()
                && characters <= config.budgets().maximumContextCharacters();
        return new SafetyResult(
                "KNOWLEDGE_QA",
                disposition,
                citationIds,
                List.copyOf(selected),
                characters,
                truncated,
                withinBudget);
    }

    private String normalize(String value) {
        String unicodeNormalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        return WHITESPACE.matcher(unicodeNormalized).replaceAll(" ");
    }

    private String currentRequestOverride(String request) {
        if (request.contains("请用中文")) {
            return "CHINESE";
        }
        if (request.contains("请用 Python")) {
            return "PYTHON";
        }
        if (request.contains("不要列表")) {
            return "PARAGRAPH";
        }
        return null;
    }

    private String memoryStyle(String memoryContent) {
        if (memoryContent.contains("Java")) {
            return "JAVA";
        }
        if (memoryContent.contains("英文")) {
            return "ENGLISH";
        }
        if (memoryContent.contains("要点")) {
            return "BULLETS";
        }
        if (memoryContent.contains("简洁")) {
            return "CONCISE";
        }
        if (memoryContent.contains("表格")) {
            return "TABLE";
        }
        throw new IllegalArgumentException("Unsupported synthetic preference: " + memoryContent);
    }

    private String defaultStyle(String memoryContent) {
        if (memoryContent.contains("Java")) {
            return "PYTHON";
        }
        if (memoryContent.contains("英文")) {
            return "CHINESE";
        }
        if (memoryContent.contains("要点") || memoryContent.contains("表格")) {
            return "PARAGRAPH";
        }
        if (memoryContent.contains("简洁")) {
            return "DETAILED";
        }
        throw new IllegalArgumentException("Unsupported synthetic preference: " + memoryContent);
    }

    enum PreferenceStrategy {
        NO_MEMORY_BASELINE,
        WITH_BOUNDED_MEMORY
    }

    record RoutingResult(String decision, String content) {
    }

    record PreferenceResult(
            String actualStyle,
            boolean expectedStyleSatisfied,
            boolean memoryApplied,
            boolean currentRequestPrecedenceSatisfied) {
    }

    record SelectedMemory(String id, int revision, int characters) {
    }

    record SafetyResult(
            String route,
            String disposition,
            List<String> citationIds,
            List<SelectedMemory> selectedMemories,
            int contextCharacters,
            boolean truncated,
            boolean withinBudget) {
    }
}
