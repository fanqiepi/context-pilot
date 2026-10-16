package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;

public final class TaskRequests {
    public record Create(@NotNull UUID clientRequestId, @NotNull UUID knowledgeBaseId,
            @NotBlank @Size(max=2000) String goal,
            @NotNull @Size(min=1,max=5) List<@NotNull UUID> documentIds) { }
    public enum Operation { START, PAUSE, RESUME, CANCEL, SUPPLEMENT }
    public record Command(@NotNull Operation operation, @NotNull @Min(0) Long expectedRevision,
            @Size(max=2000) String text) { }
    public record View(UUID id, UUID knowledgeBaseId, TaskStatus status, long revision,
            TaskState state, java.time.OffsetDateTime createdAt, java.time.OffsetDateTime updatedAt) { }
    public record Event(UUID id, long revision, String kind, String status, String checkpoint,
            java.time.OffsetDateTime createdAt) { }
    private TaskRequests() { }
}
