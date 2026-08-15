package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseHealthReportControllerTests {

    @Mock
    private KnowledgeBaseHealthReportService reportService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new KnowledgeBaseHealthReportController(reportService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsImmutableHealthReport() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        OffsetDateTime dataAsOf = OffsetDateTime.parse("2026-08-15T03:00:00Z");
        when(reportService.get(reportId)).thenReturn(new KnowledgeBaseHealthReportResponse(
                reportId,
                knowledgeBaseId,
                conversationId,
                userMessageId,
                assistantMessageId,
                CapabilityId.KNOWLEDGE_QA,
                "v2",
                KnowledgeBaseHealthStatus.HEALTHY,
                HealthCheckCompleteness.COMPLETE,
                null,
                dataAsOf,
                new EmbeddingIndexProfile(
                        "dashscope_qwen3_7_1024_v1",
                        "DASHSCOPE",
                        "qwen3.7-text-embedding",
                        1024,
                        "v1"),
                new DocumentStatusCounts(1, 0, 0, 1, 0, 0),
                0,
                0,
                "知识库健康，未发现文档处理或索引异常。",
                List.of(),
                "trace-health",
                dataAsOf.plusSeconds(1)));

        mockMvc.perform(get("/api/knowledge-base-health-reports/{id}", reportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId.toString()))
                .andExpect(jsonPath("$.knowledgeBaseId").value(knowledgeBaseId.toString()))
                .andExpect(jsonPath("$.capabilityId").value("KNOWLEDGE_QA"))
                .andExpect(jsonPath("$.capabilityVersion").value("v2"))
                .andExpect(jsonPath("$.healthStatus").value("HEALTHY"))
                .andExpect(jsonPath("$.completeness").value("COMPLETE"))
                .andExpect(jsonPath("$.dataAsOf").value("2026-08-15T03:00:00Z"))
                .andExpect(jsonPath("$.currentEmbeddingProfile.dimensions").value(1024))
                .andExpect(jsonPath("$.documentCounts.succeeded").value(1))
                .andExpect(jsonPath("$.issues").isEmpty())
                .andExpect(jsonPath("$.traceId").value("trace-health"));
    }

    @Test
    void returnsStableNotFoundError() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(reportService.get(reportId)).thenThrow(new ResourceNotFoundException(
                "HEALTH_REPORT_NOT_FOUND",
                "Knowledge base health report " + reportId + " was not found"));

        mockMvc.perform(get("/api/knowledge-base-health-reports/{id}", reportId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HEALTH_REPORT_NOT_FOUND"));
    }
}
