package io.github.fanqiepi.contextpilot.researchreport;

import java.util.UUID;
import jakarta.validation.constraints.*;

public record ReportSaveRequest(@NotNull UUID clientRequestId,@NotBlank @Size(max=120) String title,UUID conversationId){}
