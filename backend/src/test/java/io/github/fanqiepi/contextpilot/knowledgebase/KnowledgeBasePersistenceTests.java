package io.github.fanqiepi.contextpilot.knowledgebase;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentFileType;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.SourceDocumentEntity;
import io.github.fanqiepi.contextpilot.document.SourceDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.vectorstore.type=none"
})
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class KnowledgeBasePersistenceTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PGVECTOR_IMAGE)
            .withDatabaseName("context_pilot")
            .withUsername("context_pilot")
            .withPassword("context_pilot_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Autowired
    private SourceDocumentMapper sourceDocumentMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndReadsUuidIdentifier() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName("UUID persistence test");
        entity.setDescription("Verifies PostgreSQL UUID mapping");
        entity.setStatus(KnowledgeBaseStatus.ACTIVE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(knowledgeBaseMapper.insert(entity)).isOne();

        KnowledgeBaseEntity stored = knowledgeBaseMapper.selectById(id);
        assertThat(stored).isNotNull();
        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getName()).isEqualTo(entity.getName());
        assertThat(stored.getDeleted()).isZero();
    }

    @Test
    void logicallyDeletesKnowledgeBaseAndAllowsNameReuse() {
        UUID deletedId = UUID.randomUUID();
        KnowledgeBaseEntity deletedEntity = knowledgeBase(deletedId, "Reusable name");
        assertThat(knowledgeBaseMapper.insert(deletedEntity)).isOne();

        assertThat(knowledgeBaseMapper.deleteById(deletedId)).isOne();
        assertThat(knowledgeBaseMapper.selectById(deletedId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM knowledge_base WHERE id = ?",
                Integer.class,
                deletedId)).isOne();

        KnowledgeBaseEntity replacement = knowledgeBase(UUID.randomUUID(), "Reusable name");
        assertThat(knowledgeBaseMapper.insert(replacement)).isOne();
    }

    @Test
    void logicallyDeletesDocumentMetadata() {
        UUID knowledgeBaseId = UUID.randomUUID();
        assertThat(knowledgeBaseMapper.insert(knowledgeBase(knowledgeBaseId, "Document parent"))).isOne();

        UUID documentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SourceDocumentEntity document = new SourceDocumentEntity();
        document.setId(documentId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalFilename("notes.txt");
        document.setFileType(DocumentFileType.TXT);
        document.setMediaType("text/plain");
        document.setSizeBytes(5);
        document.setStorageKey("knowledge-bases/" + knowledgeBaseId + "/documents/" + documentId + "/source.txt");
        document.setSha256("0".repeat(64));
        document.setStatus(DocumentStatus.PENDING);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        assertThat(sourceDocumentMapper.insert(document)).isOne();

        assertThat(sourceDocumentMapper.deleteById(documentId)).isOne();
        assertThat(sourceDocumentMapper.selectById(documentId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM source_document WHERE id = ?",
                Integer.class,
                documentId)).isOne();
    }

    private KnowledgeBaseEntity knowledgeBase(UUID id, String name) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setStatus(KnowledgeBaseStatus.ACTIVE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
