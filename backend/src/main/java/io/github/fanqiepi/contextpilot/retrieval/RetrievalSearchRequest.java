package io.github.fanqiepi.contextpilot.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RetrievalSearchRequest(
        @NotBlank @Size(max = 2000) String query,
        @Min(1) @Max(20) Integer topK) {

    int effectiveTopK() {
        return topK == null ? 5 : topK;
    }
}
