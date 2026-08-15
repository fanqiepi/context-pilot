package io.github.fanqiepi.contextpilot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PGVECTOR_IMAGE)
            .withDatabaseName("context_pilot")
            .withUsername("context_pilot")
            .withPassword("context_pilot_test");

    @Test
    void createsPgVectorStoreSchema() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_extension
                        WHERE extname = 'vector'
                    )
                    """)).isTrue();
            for (String tableName : new String[] {
                    "conversation", "chat_message", "message_citation", "model_call", "answer_feedback",
                    "action_request", "knowledge_base_health_report", "knowledge_base_health_issue"
            }) {
                assertThat(queryBoolean(connection, """
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.tables
                            WHERE table_schema = 'public'
                              AND table_name = '%s'
                        )
                        """.formatted(tableName))).isTrue();
            }
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'answer_feedback'
                          AND constraint_name = 'answer_feedback_message_fk'
                          AND constraint_type = 'FOREIGN KEY'
                    )
                    """)).isTrue();
            assertThat(queryString(connection, """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'action_request'
                      AND column_name = 'parameters'
                    """)).isEqualTo("jsonb");
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'action_request'
                          AND constraint_name = 'action_request_assistant_message_uq'
                          AND constraint_type = 'UNIQUE'
                    )
                    """)).isTrue();
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'answer_feedback'
                          AND constraint_name = 'answer_feedback_message_uq'
                          AND constraint_type = 'UNIQUE'
                    )
                    """)).isTrue();
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name = 'vector_store'
                    )
                    """)).isTrue();
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name = 'knowledge_base'
                    )
                    """)).isTrue();
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'knowledge_base'
                          AND indexname = 'knowledge_base_name_ci_uq'
                    )
                    """)).isTrue();
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name = 'source_document'
                    )
                    """)).isTrue();
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'source_document'
                          AND constraint_name = 'source_document_knowledge_base_fk'
                          AND constraint_type = 'FOREIGN KEY'
                    )
                    """)).isTrue();
            assertThat(queryString(connection, """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'knowledge_base'
                      AND column_name = 'deleted'
                    """)).isEqualTo("smallint");
            for (String columnName : new String[] {
                    "embedding_profile_id", "embedding_provider", "embedding_model", "embedding_profile_version"
            }) {
                assertThat(queryString(connection, """
                        SELECT data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'source_document'
                          AND column_name = '%s'
                        """.formatted(columnName))).isEqualTo("character varying");
            }
            assertThat(queryString(connection, """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'source_document'
                      AND column_name = 'embedding_dimensions'
                    """)).isEqualTo("integer");
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'source_document'
                          AND constraint_name = 'source_document_embedding_index_complete_check'
                          AND constraint_type = 'CHECK'
                    )
                    """)).isTrue();
            assertThat(queryString(connection, """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'source_document'
                      AND column_name = 'processing_attempts'
                    """)).isEqualTo("integer");
            assertThat(queryString(connection, """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'source_document'
                      AND column_name = 'deleted'
                    """)).isEqualTo("smallint");
            for (String columnName : new String[] {
                    "capability_id", "capability_version", "capability_match_reason"
            }) {
                assertThat(queryString(connection, """
                        SELECT data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'chat_message'
                          AND column_name = '%s'
                        """.formatted(columnName))).isEqualTo("character varying");
            }
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'chat_message'
                          AND constraint_name = 'chat_message_capability_route_check'
                          AND constraint_type = 'CHECK'
                    )
                    """)).isTrue();
            assertThat(queryString(connection, """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'knowledge_base_health_report'
                      AND column_name = 'issue_count'
                    """)).isEqualTo("bigint");
            for (String indexName : new String[] {
                    "knowledge_base_health_report_assistant_message_uq",
                    "knowledge_base_health_issue_report_document_type_uq",
                    "vector_store_health_metadata_idx"
            }) {
                assertThat(queryBoolean(connection, """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_indexes
                            WHERE schemaname = 'public'
                              AND indexname = '%s'
                        )
                        """.formatted(indexName))).isTrue();
            }
            assertThat(queryBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'knowledge_base_health_issue'
                          AND constraint_name = 'knowledge_base_health_issue_report_fk'
                          AND constraint_type = 'FOREIGN KEY'
                    )
                    """)).isTrue();
            assertThat(queryString(connection, """
                    SELECT pg_get_constraintdef(constraint_info.oid)
                    FROM pg_constraint constraint_info
                    JOIN pg_class table_info ON constraint_info.conrelid = table_info.oid
                    JOIN pg_namespace schema_info ON table_info.relnamespace = schema_info.oid
                    WHERE schema_info.nspname = 'public'
                      AND table_info.relname = 'chat_message'
                      AND constraint_info.conname = 'chat_message_capability_match_reason_check'
                    """)).contains("EXPLICIT_KNOWLEDGE_BASE_HEALTH");
            assertThat(queryString(connection, """
                    SELECT format_type(attribute.atttypid, attribute.atttypmod)
                    FROM pg_attribute attribute
                    JOIN pg_class table_info ON attribute.attrelid = table_info.oid
                    JOIN pg_namespace schema_info ON table_info.relnamespace = schema_info.oid
                    WHERE schema_info.nspname = 'public'
                      AND table_info.relname = 'vector_store'
                      AND attribute.attname = 'embedding'
                    """)).isEqualTo("vector(1024)");
        }
    }

    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
