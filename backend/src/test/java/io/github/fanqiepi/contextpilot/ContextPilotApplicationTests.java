package io.github.fanqiepi.contextpilot;

import io.github.fanqiepi.contextpilot.document.DocumentService;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                + "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
class ContextPilotApplicationTests {

    @MockitoBean
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @MockitoBean
    private DocumentService documentService;

    @Test
    void contextLoads() {
    }
}
