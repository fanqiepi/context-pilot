package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record UpgradeStartRequest(
        @NotNull UUID clientRequestId,
        @NotNull UUID knowledgeBaseId,
        @NotNull @Size(min=1,max=3) List<@NotNull UUID> documentIds,
        @NotBlank String repositoryKey,
        @NotNull @Size(min=1,max=3) List<@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,100}") String> releaseTags,
        @NotBlank @Size(max=2000) String question,
        UUID retryOfRunId) {
    public UpgradeStartRequest {
        documentIds = documentIds == null ? null : List.copyOf(documentIds);
        releaseTags = releaseTags == null ? null : List.copyOf(releaseTags);
    }
}
