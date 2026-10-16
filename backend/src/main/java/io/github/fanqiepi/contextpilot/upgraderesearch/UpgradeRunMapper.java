package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UpgradeRunMapper extends BaseMapper<UpgradeRunEntity> {
    @Select("SELECT id FROM action_request WHERE upgrade_run_id=#{run} AND deleted=0 ORDER BY created_at DESC,id DESC LIMIT 1")
    UUID latestSaveAction(UUID run);
    @Select("SELECT id FROM knowledge_base WHERE id=#{id} AND deleted=0 FOR UPDATE")
    UUID lockKnowledgeBase(UUID id);

    @Select("SELECT * FROM upgrade_research_run WHERE owner_id=#{owner} AND knowledge_base_id=#{kb} AND deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<UpgradeRunEntity> listOwned(@Param("owner") UUID owner,@Param("kb") UUID kb,@Param("limit") int limit,@Param("offset") long offset);

    @Update("""
        UPDATE upgrade_research_run SET deleted=1,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=0
          AND status NOT IN ('QUEUED','RUNNING')
          AND NOT EXISTS(SELECT 1 FROM research_report WHERE run_id=#{id} AND deleted=0)
          AND NOT EXISTS(SELECT 1 FROM action_request WHERE upgrade_run_id=#{id} AND deleted=0
            AND (status='EXECUTING' OR (status='PENDING_CONFIRMATION' AND expires_at>CURRENT_TIMESTAMP)))
        """)
    int deleteTerminal(UUID id);
    @Select("SELECT id FROM source_document WHERE id=#{id} AND deleted=0 FOR SHARE")
    UUID lockDocument(UUID id);
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    String lockKey(String key);

    @Select("SELECT * FROM upgrade_research_run WHERE owner_id=#{owner} AND client_request_id=#{key}")
    UpgradeRunEntity byRequest(@Param("owner") UUID owner, @Param("key") UUID key);

    @Select("SELECT * FROM upgrade_research_run WHERE owner_id=#{owner} AND id=#{id} AND deleted=0 FOR UPDATE")
    UpgradeRunEntity lockOwned(@Param("owner") UUID owner, @Param("id") UUID id);

    @Select("SELECT * FROM upgrade_research_run WHERE owner_id=#{owner} AND id=#{id} AND deleted=0")
    UpgradeRunEntity owned(@Param("owner") UUID owner, @Param("id") UUID id);

    @Insert("""
        INSERT INTO upgrade_research_run(id,owner_id,knowledge_base_id,client_request_id,request_hash,
          question,document_snapshots,repository_key,release_tags,retry_of_run_id,budget_version,
          prompt_version,schema_version,status,deadline,trace_id,created_at,updated_at)
        VALUES (#{id},#{ownerId},#{knowledgeBaseId},#{clientRequestId},#{requestHash},#{question},
          CAST(#{documentSnapshots} AS jsonb),#{repositoryKey},CAST(#{releaseTags} AS jsonb),
          #{retryOfRunId},#{budgetVersion},#{promptVersion},#{schemaVersion},'QUEUED',#{deadline},
          #{traceId},#{createdAt},#{updatedAt})
        """)
    int insertRun(UpgradeRunEntity run);

    @Select("""
        SELECT sd.id, sd.knowledge_base_id, sd.original_filename AS filename, sd.sha256,
          sd.embedding_profile_id AS profile_id, sd.indexed_at, sd.status,
          EXISTS (SELECT 1 FROM vector_store vs WHERE vs.metadata->>'document_id'=sd.id::text
            AND vs.metadata->>'knowledge_base_id'=sd.knowledge_base_id::text
            AND vs.metadata->>'embedding_profile_id'=#{profile}) AS vector_present
        FROM source_document sd WHERE sd.id=#{id} AND sd.deleted=0
        """)
    UpgradeDocumentSnapshot document(@Param("id") UUID id, @Param("profile") String profile);

    @Update("""
        UPDATE upgrade_research_run SET status='RUNNING',revision=revision+1,updated_at=#{now}
        WHERE id=#{id} AND status='QUEUED' AND deleted=0 AND deadline>#{now}
        """)
    int claim(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update("""
        UPDATE upgrade_research_run SET status=#{status},error_code=#{error},revision=revision+1,updated_at=#{now}
        WHERE id=#{id} AND status IN ('QUEUED','RUNNING') AND deleted=0
        """)
    int stop(@Param("id") UUID id, @Param("status") UpgradeRunStatus status,
            @Param("error") String error, @Param("now") OffsetDateTime now);

    @Select("SELECT id FROM upgrade_research_run WHERE deleted=0 AND status IN ('QUEUED','RUNNING') AND deadline<=#{now} ORDER BY deadline LIMIT 100")
    List<UUID> expired(OffsetDateTime now);

    @Select("SELECT id FROM upgrade_research_run WHERE deleted=0 AND status IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT 100")
    List<UUID> active();

    @Select("SELECT * FROM upgrade_research_run WHERE id=#{id} AND deleted=0 FOR UPDATE")
    UpgradeRunEntity lockInternal(UUID id);

    @Update("""
        UPDATE upgrade_research_model_call SET status=#{status},error_code=#{error},finished_at=#{now}
        WHERE run_id=#{id} AND status='STARTED'
        """)
    int stopModels(@Param("id") UUID id, @Param("status") String status,
            @Param("error") String error, @Param("now") OffsetDateTime now);

    @Update("""
        UPDATE upgrade_research_tool_call SET status=#{status},error_code=#{error},finished_at=#{now}
        WHERE run_id=#{id} AND status='STARTED'
        """)
    int stopTools(@Param("id") UUID id, @Param("status") String status,
            @Param("error") String error, @Param("now") OffsetDateTime now);
}
