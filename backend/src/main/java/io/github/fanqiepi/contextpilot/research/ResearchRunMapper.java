package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResearchRunMapper {
    String COLUMNS = """
            id, knowledge_base_id, conversation_id, user_message_id, assistant_message_id,
            client_request_id, request_fingerprint, task_type, plan_version,
            selected_document_ids::text AS selected_document_ids_json,
            execution_status, answer_status,
            max_plan_steps, max_retrieval_calls, max_raw_hits, max_evidence_chunks,
            max_evidence_characters, hard_timeout_millis,
            actual_retrieval_calls, actual_raw_hits, actual_evidence_chunks,
            actual_evidence_characters, prompt_tokens, completion_tokens, total_tokens,
            current_step_ordinal, error_code, error_summary, trace_id, retry_of_run_id,
            started_at, cancelled_at, completed_at, created_at, updated_at, deleted
            """;

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{clientRequestId}::text, 0))")
    Long lockClientRequest(UUID clientRequestId);

    @Insert("""
            INSERT INTO research_run (
                id, knowledge_base_id, conversation_id, user_message_id, assistant_message_id,
                client_request_id, request_fingerprint, task_type, plan_version, selected_document_ids,
                execution_status, answer_status, max_plan_steps, max_retrieval_calls, max_raw_hits,
                max_evidence_chunks, max_evidence_characters, hard_timeout_millis,
                actual_retrieval_calls, actual_raw_hits, actual_evidence_chunks,
                actual_evidence_characters, trace_id, retry_of_run_id,
                created_at, updated_at, deleted
            ) VALUES (
                #{id}, #{knowledgeBaseId}, #{conversationId}, #{userMessageId}, #{assistantMessageId},
                #{clientRequestId}, #{requestFingerprint}, #{taskType}, #{planVersion},
                CAST(#{selectedDocumentIdsJson} AS jsonb),
                #{executionStatus}, #{answerStatus}, #{maxPlanSteps}, #{maxRetrievalCalls}, #{maxRawHits},
                #{maxEvidenceChunks}, #{maxEvidenceCharacters}, #{hardTimeoutMillis},
                #{actualRetrievalCalls}, #{actualRawHits}, #{actualEvidenceChunks},
                #{actualEvidenceCharacters}, #{traceId}, #{retryOfRunId},
                #{createdAt}, #{updatedAt}, #{deleted}
            )
            """)
    int insert(ResearchRunEntity entity);

    @Select("SELECT " + COLUMNS + " FROM research_run WHERE id = #{id} AND deleted = 0")
    ResearchRunEntity selectById(UUID id);

    @Select("SELECT " + COLUMNS
            + " FROM research_run WHERE client_request_id = #{clientRequestId} AND deleted = 0")
    ResearchRunEntity selectByClientRequestId(UUID clientRequestId);

    @Select("SELECT " + COLUMNS
            + " FROM research_run WHERE deleted = 0 AND execution_status IN ('PLANNING','EXECUTING','SYNTHESIZING')")
    List<ResearchRunEntity> selectActive();

    @Select({"<script>", "SELECT " + COLUMNS, "FROM research_run",
            "WHERE deleted = 0 AND assistant_message_id IN",
            "<foreach collection='messageIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<ResearchRunEntity> selectByAssistantMessageIds(@Param("messageIds") List<UUID> messageIds);

    @Update("""
            UPDATE research_run SET execution_status = 'EXECUTING', started_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND execution_status = 'PLANNING'
            """)
    int claimExecution(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_run SET current_step_ordinal = #{ordinal}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND execution_status = 'EXECUTING'
            """)
    int updateCurrentStep(@Param("id") UUID id, @Param("ordinal") int ordinal, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_run SET execution_status = 'SYNTHESIZING',
                actual_retrieval_calls = #{retrievalCalls}, actual_raw_hits = #{rawHits},
                actual_evidence_chunks = #{evidenceChunks}, actual_evidence_characters = #{evidenceCharacters},
                updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND execution_status = 'EXECUTING'
            """)
    int beginSynthesis(
            @Param("id") UUID id,
            @Param("retrievalCalls") int retrievalCalls,
            @Param("rawHits") int rawHits,
            @Param("evidenceChunks") int evidenceChunks,
            @Param("evidenceCharacters") int evidenceCharacters,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_run SET execution_status = #{executionStatus}, answer_status = #{answerStatus},
                prompt_tokens = #{promptTokens}, completion_tokens = #{completionTokens}, total_tokens = #{totalTokens},
                error_code = #{errorCode}, error_summary = #{errorSummary},
                completed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND execution_status = 'SYNTHESIZING'
            """)
    int complete(
            @Param("id") UUID id,
            @Param("executionStatus") ResearchExecutionStatus executionStatus,
            @Param("answerStatus") ResearchAnswerStatus answerStatus,
            @Param("promptTokens") Integer promptTokens,
            @Param("completionTokens") Integer completionTokens,
            @Param("totalTokens") Integer totalTokens,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_run SET execution_status = 'FAILED', answer_status = NULL,
                error_code = #{errorCode}, error_summary = #{errorSummary},
                completed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0
              AND execution_status IN ('PLANNING', 'EXECUTING', 'SYNTHESIZING')
            """)
    int fail(
            @Param("id") UUID id,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_run SET execution_status = 'CANCELLED', answer_status = NULL,
                cancelled_at = #{now}, completed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0
              AND execution_status IN ('PLANNING', 'EXECUTING', 'SYNTHESIZING')
            """)
    int cancel(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_run SET execution_status = 'FAILED', answer_status = NULL,
                error_code = 'RESEARCH_RUN_INTERRUPTED',
                error_summary = 'Research run was interrupted by application restart',
                completed_at = #{now}, updated_at = #{now}
            WHERE deleted = 0 AND execution_status IN ('PLANNING', 'EXECUTING', 'SYNTHESIZING')
            """)
    int failInterrupted(@Param("now") OffsetDateTime now);
}
