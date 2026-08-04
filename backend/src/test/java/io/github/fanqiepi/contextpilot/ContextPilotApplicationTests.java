package io.github.fanqiepi.contextpilot;

import io.github.fanqiepi.contextpilot.document.DocumentService;
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
    private DocumentService documentService;

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
                .andExpect(jsonPath("$.paths['/api/knowledge-bases/{id}']").exists());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }
}
