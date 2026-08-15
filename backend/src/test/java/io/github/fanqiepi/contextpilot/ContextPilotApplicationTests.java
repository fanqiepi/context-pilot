package io.github.fanqiepi.contextpilot;

import io.github.fanqiepi.contextpilot.action.ActionRequestMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.chat.MessageCitationMapper;
import io.github.fanqiepi.contextpilot.chat.ModelCallMapper;
import io.github.fanqiepi.contextpilot.document.DocumentService;
import io.github.fanqiepi.contextpilot.document.SourceDocumentMapper;
import io.github.fanqiepi.contextpilot.feedback.AnswerFeedbackMapper;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthIssueMapper;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportMapper;
import io.github.fanqiepi.contextpilot.health.PostgresKnowledgeBaseHealthDataPort;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
})
class ContextPilotApplicationTests {

    @Autowired
    private WebApplicationContext applicationContext;

    @MockitoBean
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @MockitoBean
    private SourceDocumentMapper sourceDocumentMapper;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private ConversationMapper conversationMapper;

    @MockitoBean
    private ChatMessageMapper chatMessageMapper;

    @MockitoBean
    private MessageCitationMapper messageCitationMapper;

    @MockitoBean
    private ModelCallMapper modelCallMapper;

    @MockitoBean
    private AnswerFeedbackMapper answerFeedbackMapper;

    @MockitoBean
    private ActionRequestMapper actionRequestMapper;

    @MockitoBean
    private KnowledgeBaseHealthReportMapper healthReportMapper;

    @MockitoBean
    private KnowledgeBaseHealthIssueMapper healthIssueMapper;

    @MockitoBean
    private PostgresKnowledgeBaseHealthDataPort healthDataPort;

    @Test
    void contextLoads() {
    }

    @Test
    void exposesKnife4jAndOpenApiDocumentation() throws Exception {
        var mockMvc = webAppContextSetup(applicationContext).build();

        mockMvc.perform(get("/doc.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ContextPilot API"))
                .andExpect(jsonPath("$.paths['/api/knowledge-bases']").exists())
                .andExpect(jsonPath("$.paths['/api/knowledge-bases/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/documents/{documentId}/retry']").exists())
                .andExpect(jsonPath("$.paths['/api/knowledge-bases/{knowledgeBaseId}/search']").exists())
                .andExpect(jsonPath("$.paths['/api/chat']").exists())
                .andExpect(jsonPath("$.paths['/api/chat/stream']").exists())
                .andExpect(jsonPath("$.paths['/api/conversations']").exists())
                .andExpect(jsonPath("$.paths['/api/conversations/{conversationId}/messages']").exists())
                .andExpect(jsonPath("$.paths['/api/messages/{messageId}/feedback']").exists())
                .andExpect(jsonPath("$.paths['/api/action-requests/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/action-requests/{id}/confirm']").exists())
                .andExpect(jsonPath("$.paths['/api/action-requests/{id}/reject']").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/knowledge-base-health-reports/{reportId}/issues/{issueId}/action-request']")
                        .exists());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }
}
