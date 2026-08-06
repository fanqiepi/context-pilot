package io.github.fanqiepi.contextpilot.retrieval;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RetrievalControllerTests {

    @Mock
    private RetrievalService retrievalService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RetrievalController(retrievalService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void searchesKnowledgeBase() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(retrievalService.search(any(), any())).thenReturn(List.of(new RetrievalResultResponse(
                UUID.randomUUID().toString(), documentId, "notes.txt", 0, null, "Spring Boot content", 0.9)));

        mockMvc.perform(post("/api/knowledge-bases/{id}/search", knowledgeBaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"Spring Boot","topK":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value(documentId.toString()))
                .andExpect(jsonPath("$[0].content").value("Spring Boot content"));
    }

    @Test
    void validatesSearchRequest() throws Exception {
        mockMvc.perform(post("/api/knowledge-bases/{id}/search", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":" ","topK":21}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
