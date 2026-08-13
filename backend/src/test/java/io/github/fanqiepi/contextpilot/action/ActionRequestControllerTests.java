package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ActionRequestControllerTests {

    @Mock
    private ActionRequestService actionRequestService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ActionRequestController(actionRequestService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getsAndConfirmsWithoutClientSuppliedExecutionParameters() throws Exception {
        UUID id = UUID.randomUUID();
        when(actionRequestService.get(id)).thenReturn(actionRequest(id, ActionRequestStatus.PENDING_CONFIRMATION));
        when(actionRequestService.confirm(id)).thenReturn(actionRequest(id, ActionRequestStatus.SUCCEEDED));

        mockMvc.perform(get("/api/action-requests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.actionType").value("CREATE_KNOWLEDGE_BASE"))
                .andExpect(jsonPath("$.parameters.name").value("Java 学习"));

        mockMvc.perform(post("/api/action-requests/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void returnsConflictWhenTerminalActionCannotBeRejected() throws Exception {
        UUID id = UUID.randomUUID();
        when(actionRequestService.reject(id)).thenThrow(new ConflictException(
                "ACTION_REQUEST_STATUS_CONFLICT", "Only a pending action request can be rejected"));

        mockMvc.perform(post("/api/action-requests/{id}/reject", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_REQUEST_STATUS_CONFLICT"));
    }

    private ActionRequestResponse actionRequest(UUID id, ActionRequestStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-13T08:00:00Z");
        return new ActionRequestResponse(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CapabilityId.BUSINESS_ACTION,
                "v1",
                ActionType.CREATE_KNOWLEDGE_BASE,
                new CreateKnowledgeBaseActionParameters("Java 学习", null),
                "确认后将创建知识库“Java 学习”。",
                status,
                status == ActionRequestStatus.SUCCEEDED ? "知识库已创建" : null,
                null,
                "trace-action",
                now.plusMinutes(30),
                status == ActionRequestStatus.SUCCEEDED ? now.plusMinutes(1) : null,
                status == ActionRequestStatus.SUCCEEDED ? now.plusMinutes(1) : null,
                now,
                now);
    }
}
