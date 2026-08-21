package io.github.fanqiepi.contextpilot.observability;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AiCallLoggerTests {

    @Test
    void emitsStableStructuredLifecycleFieldsAndFailureCause(CapturedOutput output) {
        UUID callId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        AiCallContext context = new AiCallContext(
                "RESEARCH_SYNTHESIS", "DEEPSEEK", "deepseek-v4-flash", "trace-log", callId,
                "researchRun", resourceId, "prompt-v2", 420, 6, 3000);

        AiCallLogger.started(context);
        AiCallLogger.succeeded(context, 125, 30, 12, 42, null);
        AiCallLogger.failed(
                context, 140,
                new IllegalStateException(
                        "Authorization: top-secret-value",
                        new RuntimeException("provider rejected request with Bearer private-token")));
        AiCallLogger.cancelled(context, 150);

        assertThat(output)
                .contains("ai.call.started operation=RESEARCH_SYNTHESIS")
                .contains("provider=DEEPSEEK")
                .contains("model=deepseek-v4-flash")
                .contains("traceId=trace-log")
                .contains("callId=" + callId)
                .contains("resourceType=researchRun resourceId=" + resourceId)
                .contains("promptVersion=prompt-v2")
                .contains("inputCharacters=420 itemCount=6 maxOutputTokens=3000")
                .contains("ai.call.succeeded operation=RESEARCH_SYNTHESIS")
                .contains("latencyMs=125 promptTokens=30 completionTokens=12 totalTokens=42")
                .contains("ai.call.failed operation=RESEARCH_SYNTHESIS")
                .contains("errorType=java.lang.RuntimeException "
                        + "errorMessage=provider rejected request with Bearer [REDACTED]")
                .contains("ai.call.cancelled operation=RESEARCH_SYNTHESIS")
                .doesNotContain("top-secret-value")
                .doesNotContain("private-token");
    }
}
