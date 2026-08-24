package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserMemoryControllerTests {

    @Mock
    private UserMemoryService userMemoryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserMemoryController(userMemoryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void listsOwnedMemoriesWithOptionalFilters() throws Exception {
        UUID id = UUID.randomUUID();
        when(userMemoryService.list(UserMemoryStatus.ACTIVE, false)).thenReturn(List.of(response(id)));

        mockMvc.perform(get("/api/user-memories")
                        .queryParam("status", "ACTIVE")
                        .queryParam("expired", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].content").value("回答尽量简洁"))
                .andExpect(jsonPath("$[0].sourceType").value("CHAT_CONFIRMED"));
    }

    @Test
    void returnsDetailWithoutHistoricalContent() throws Exception {
        UUID id = UUID.randomUUID();
        when(userMemoryService.get(id)).thenReturn(detail(id));

        mockMvc.perform(get("/api/user-memories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRevision").value(2))
                .andExpect(jsonPath("$.revisions[0].revision").value(2))
                .andExpect(jsonPath("$.revisions[0].content").doesNotExist());
    }

    @Test
    void updatesWithStrongVersionedFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(userMemoryService.update(eq(id), any())).thenReturn(detail(id));

        mockMvc.perform(patch("/api/user-memories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"content":"回答尽量简洁","status":"ACTIVE","expiresAt":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void requiresVersionForManagementUpdates() throws Exception {
        mockMvc.perform(patch("/api/user-memories/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsClientSuppliedOwnerFields() throws Exception {
        mockMvc.perform(patch("/api/user-memories/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"status":"DISABLED","ownerUserId":"00000000-0000-0000-0000-000000000002"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(userMemoryService);
    }

    @Test
    void mapsOutOfScopeMemoryToNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(userMemoryService.get(id)).thenThrow(new ResourceNotFoundException(
                "USER_MEMORY_NOT_FOUND", "Response preference was not found"));

        mockMvc.perform(get("/api/user-memories/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_MEMORY_NOT_FOUND"));
    }

    @Test
    void logicallyDeletesMemory() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/user-memories/{id}", id))
                .andExpect(status().isNoContent());

        verify(userMemoryService).delete(id);
    }

    private UserMemoryResponse response(UUID id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new UserMemoryResponse(
                id,
                UserMemoryType.RESPONSE_PREFERENCE,
                UserMemoryScope.GLOBAL,
                UserMemoryStatus.ACTIVE,
                "回答尽量简洁",
                1,
                UserMemorySourceType.CHAT_CONFIRMED,
                null,
                false,
                1,
                now,
                now);
    }

    private UserMemoryDetailResponse detail(UUID id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new UserMemoryDetailResponse(
                id,
                UserMemoryType.RESPONSE_PREFERENCE,
                UserMemoryScope.GLOBAL,
                UserMemoryStatus.ACTIVE,
                "回答尽量简洁",
                2,
                UserMemorySourceType.MANAGEMENT,
                null,
                false,
                2,
                now,
                now,
                List.of(new UserMemoryRevisionSummaryResponse(
                        2, UserMemorySourceType.MANAGEMENT, "Management update: content corrected", now)));
    }
}
