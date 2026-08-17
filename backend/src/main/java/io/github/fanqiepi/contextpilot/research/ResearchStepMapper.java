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
public interface ResearchStepMapper {
    String COLUMNS = """
            id, run_id, ordinal, goal, query, document_ids::text AS document_ids_json,
            status, hit_count, retained_evidence_count, latency_ms, error_summary,
            created_at, updated_at, deleted
            """;

    @Insert("""
            INSERT INTO research_step (
                id, run_id, ordinal, goal, query, document_ids, status,
                hit_count, retained_evidence_count, created_at, updated_at, deleted
            ) VALUES (
                #{id}, #{runId}, #{ordinal}, #{goal}, #{query}, CAST(#{documentIdsJson} AS jsonb),
                #{status}, #{hitCount}, #{retainedEvidenceCount}, #{createdAt}, #{updatedAt}, #{deleted}
            )
            """)
    int insert(ResearchStepEntity entity);

    @Select("SELECT " + COLUMNS
            + " FROM research_step WHERE run_id = #{runId} AND deleted = 0 ORDER BY ordinal")
    List<ResearchStepEntity> selectByRunId(UUID runId);

    @Update("""
            UPDATE research_step SET status = 'RUNNING', updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND status = 'PENDING'
            """)
    int markRunning(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_step SET status = #{status}, hit_count = #{hitCount},
                retained_evidence_count = #{retainedCount}, latency_ms = #{latencyMs},
                error_summary = #{errorSummary}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND status = 'RUNNING'
            """)
    int complete(
            @Param("id") UUID id,
            @Param("status") ResearchStepStatus status,
            @Param("hitCount") int hitCount,
            @Param("retainedCount") int retainedCount,
            @Param("latencyMs") long latencyMs,
            @Param("errorSummary") String errorSummary,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_step SET status = 'CANCELLED', updated_at = #{now}
            WHERE run_id = #{runId} AND deleted = 0 AND status IN ('PENDING', 'RUNNING')
            """)
    int cancelRemaining(@Param("runId") UUID runId, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE research_step SET status = 'PARTIAL', error_summary = #{errorSummary}, updated_at = #{now}
            WHERE run_id = #{runId} AND deleted = 0 AND status IN ('PENDING', 'RUNNING')
            """)
    int partialRemaining(
            @Param("runId") UUID runId,
            @Param("errorSummary") String errorSummary,
            @Param("now") OffsetDateTime now);
}
