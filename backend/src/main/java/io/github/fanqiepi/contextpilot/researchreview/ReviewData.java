package io.github.fanqiepi.contextpilot.researchreview;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
import io.github.fanqiepi.contextpilot.taskworkspace.TaskState;
import tools.jackson.databind.json.JsonMapper;

public final class ReviewData {
    static final JsonMapper JSON = JsonMapper.builder().build();
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;
        public boolean active() { return this == QUEUED || this == RUNNING; }
    }
    public enum Verdict { SUPPORTED, PARTIAL, UNSUPPORTED, CONTRADICTED }
    public enum Overall { ISSUES_FOUND, NEEDS_MORE_EVIDENCE, NO_ISSUES_FOUND }
    public record Create(@NotNull UUID clientRequestId, @NotNull @Min(0) Long expectedTaskRevision) { }
    public record Cancel(@NotNull @Min(0) Long expectedRevision) { }
    public record Snapshot(UUID taskId, long taskRevision, int round, UUID knowledgeBaseId,
            String goal, List<String> supplements, String answer, List<TaskState.Source> sources,
            List<TaskState.Evidence> evidence) { }
    public record Claim(String claim, Verdict verdict, List<String> evidenceIds, String explanation) { }
    public record Result(List<Claim> claims, List<String> gaps, String suggestedSupplement) { }
    public record View(UUID id, UUID taskId, long taskRevision, int round, Status status, long revision,
            Overall overallVerdict, String errorCode, String freshness, Snapshot snapshot, Result result,
            TaskState.Call usage, String traceId, String promptVersion, OffsetDateTime createdAt) { }
    static String write(Object value) { return JSON.writeValueAsString(value); }
    static <T> T read(String text, Class<T> type) { return JSON.readValue(text, type); }
    private ReviewData() { }
}
