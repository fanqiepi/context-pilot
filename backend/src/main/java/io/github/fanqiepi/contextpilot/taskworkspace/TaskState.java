package io.github.fanqiepi.contextpilot.taskworkspace;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persisted, user-visible task context; never model hidden reasoning. */
public class TaskState {
    public String goal;
    public List<String> supplements = new ArrayList<>();
    public List<Source> sources = new ArrayList<>();
    public int round = 1;
    public int activations = 1;
    public String phase = "PLANNING";
    public OffsetDateTime deadline;
    public Plan plan;
    public List<StepResult> completed = new ArrayList<>();
    public List<Evidence> evidence = new ArrayList<>();
    public List<Call> calls = new ArrayList<>();
    public String answer;
    public String errorCode;
    public String traceId = UUID.randomUUID().toString();

    public record Source(UUID id, String filename, String sha256, String profileId, String indexedAt) { }
    public record Plan(List<String> questions, List<Step> steps) { }
    public record Step(String title, String tool, String query, List<UUID> documentIds) { }
    public record StepResult(int index, int hits, String status) { }
    public record Evidence(String id, UUID documentId, String filename, String chunkId, Integer page, String text) { }
    public record Call(String phase, String status, String model, Long inputTokens, Long outputTokens,
            Long totalTokens, long latencyMs) { }
}
