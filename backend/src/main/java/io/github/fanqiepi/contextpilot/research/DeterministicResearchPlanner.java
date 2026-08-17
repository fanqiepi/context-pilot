package io.github.fanqiepi.contextpilot.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import org.springframework.stereotype.Component;

@Component
public class DeterministicResearchPlanner {

    public static final String PLAN_VERSION = "document-comparison-fixed-v1";

    private static final List<Dimension> DIMENSIONS = List.of(
            dimension("deployment", "部署方式", "部署"),
            dimension("limits", "限制条件", "限制", "配额"),
            dimension("security", "安全机制", "安全"),
            dimension("observability", "可观测性", "监控"),
            dimension("recovery", "恢复机制", "恢复", "容灾"),
            dimension("cost", "成本模型", "成本", "计费"),
            dimension("compliance", "合规认证", "合规"),
            dimension("offline_mode", "离线模式", "离线运行"),
            dimension("data_residency", "数据驻留", "地域存储"),
            dimension("airgap", "物理隔离", "隔离网部署"));

    public ResearchPlan plan(String question, List<UUID> selectedDocumentIds) {
        String normalizedQuestion = normalize(question);
        List<DetectedDimension> detected = detect(normalizedQuestion);
        if (detected.size() > ResearchBudget.V1.maximumPlanSteps()) {
            throw new BadRequestException(
                    "RESEARCH_PLAN_INVALID",
                    "Comparison question contains more dimensions than the fixed plan budget allows");
        }
        List<ResearchPlanStep> steps = new ArrayList<>();
        if (detected.isEmpty()) {
            steps.add(step(1, "比较所选文档与问题相关的信息", normalizedQuestion, selectedDocumentIds));
        } else {
            int ordinal = 1;
            for (DetectedDimension item : detected) {
                String query = item.dimension().queryText();
                steps.add(step(
                        ordinal++,
                        "比较所选文档的" + item.dimension().displayName(),
                        query,
                        selectedDocumentIds));
            }
        }
        ResearchPlan plan = new ResearchPlan(PLAN_VERSION, ResearchTaskType.DOCUMENT_COMPARISON, steps);
        new ResearchPlanValidator().validate(plan, selectedDocumentIds, ResearchBudget.V1);
        return plan;
    }

    private List<DetectedDimension> detect(String question) {
        List<DetectedDimension> detected = new ArrayList<>();
        for (Dimension dimension : DIMENSIONS) {
            int first = Integer.MAX_VALUE;
            for (String alias : dimension.aliases()) {
                int position = question.indexOf(alias.toLowerCase(Locale.ROOT));
                if (position >= 0) {
                    first = Math.min(first, position);
                }
            }
            if (first != Integer.MAX_VALUE) {
                detected.add(new DetectedDimension(dimension, first));
            }
        }
        detected.sort(Comparator.comparingInt(DetectedDimension::position));
        return List.copyOf(new LinkedHashSet<>(detected));
    }

    private ResearchPlanStep step(int ordinal, String goal, String query, List<UUID> documentIds) {
        return new ResearchPlanStep(UUID.randomUUID(), ordinal, goal, query, documentIds);
    }

    private String normalize(String question) {
        if (question == null || question.isBlank()) {
            throw new BadRequestException("RESEARCH_REQUEST_INVALID", "Research question must not be blank");
        }
        return question.strip().replaceAll("\\s+", " ");
    }

    private static Dimension dimension(String id, String... aliases) {
        List<String> values = new ArrayList<>();
        values.add(id);
        values.addAll(List.of(aliases));
        return new Dimension(id, aliases[0], String.join(" ", values), List.copyOf(values));
    }

    private record Dimension(String id, String displayName, String queryText, List<String> aliases) {
    }

    private record DetectedDimension(Dimension dimension, int position) {
    }
}
