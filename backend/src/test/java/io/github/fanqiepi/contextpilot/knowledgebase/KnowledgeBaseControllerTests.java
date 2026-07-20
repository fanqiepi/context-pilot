package io.github.fanqiepi.contextpilot.knowledgebase;

import java.time.OffsetDateTime;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerTests {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(knowledgeBaseService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void createsKnowledgeBase() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeBaseService.create(any())).thenReturn(response(id, "Notes"));

        mockMvc.perform(post("/api/knowledge-bases")
                        .header("X-Request-Id", "test-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Notes","description":"Project notes"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/knowledge-bases/" + id))
                .andExpect(header().string("X-Request-Id", "test-request"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Notes"));
    }

    @Test
    void listsKnowledgeBases() throws Exception {
        when(knowledgeBaseService.list()).thenReturn(List.of(response(UUID.randomUUID(), "Notes")));

        mockMvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Notes"));
    }

    @Test
    void rejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void mapsMissingResourceToNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeBaseService.get(id)).thenThrow(
                new ResourceNotFoundException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base was not found"));

        mockMvc.perform(get("/api/knowledge-bases/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
    }

    @Test
    void updatesKnowledgeBase() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeBaseService.update(any(), any())).thenReturn(response(id, "Updated"));

        mockMvc.perform(patch("/api/knowledge-bases/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void deletesKnowledgeBase() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/knowledge-bases/{id}", id))
                .andExpect(status().isNoContent());

        verify(knowledgeBaseService).delete(id);
    }

    private KnowledgeBaseResponse response(UUID id, String name) {
        OffsetDateTime now = OffsetDateTime.now();
        return new KnowledgeBaseResponse(id, name, "Project notes", KnowledgeBaseStatus.ACTIVE, now, now);
    }
}
