package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.List;
import java.util.UUID;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    @Select("SELECT id FROM knowledge_base WHERE id=#{id} AND deleted=0 FOR SHARE")
    UUID lockBase(UUID id);
    record SourceFact(UUID id, UUID knowledgeBaseId, String filename, String sha256,
            String profileId, java.time.OffsetDateTime indexedAt, String status, boolean vectorPresent) { }
    @Select("""
        SELECT sd.id, sd.knowledge_base_id, sd.original_filename AS filename, sd.sha256,
          sd.embedding_profile_id AS profile_id, sd.indexed_at, sd.status,
          EXISTS(SELECT 1 FROM vector_store vs WHERE vs.metadata->>'document_id'=sd.id::text
            AND vs.metadata->>'knowledge_base_id'=sd.knowledge_base_id::text
            AND vs.metadata->>'embedding_profile_id'=#{profile}) AS vector_present
        FROM source_document sd WHERE sd.id=#{id} AND sd.deleted=0 FOR SHARE OF sd
        """)
    SourceFact source(@Param("id") UUID id, @Param("profile") String profile);
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    String serialize(String key);
    @Select("SELECT * FROM research_task WHERE owner_id=#{owner} AND client_request_id=#{key}")
    TaskEntity byRequest(@Param("owner") UUID owner, @Param("key") UUID key);
    @Select("SELECT * FROM research_task WHERE id=#{id} AND owner_id=#{owner} AND deleted=0 FOR UPDATE")
    TaskEntity lockOwned(@Param("id") UUID id, @Param("owner") UUID owner);
    @Select("SELECT * FROM research_task WHERE id=#{id} AND deleted=0 FOR UPDATE")
    TaskEntity lock(UUID id);
    @Select("SELECT * FROM research_task WHERE id=#{id} AND owner_id=#{owner} AND deleted=0")
    TaskEntity owned(@Param("id") UUID id, @Param("owner") UUID owner);
    @Select("SELECT * FROM research_task WHERE owner_id=#{owner} AND deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<TaskEntity> list(@Param("owner") UUID owner, @Param("limit") int limit, @Param("offset") long offset);
    @Select("SELECT id FROM research_task WHERE status IN ('PLANNING','RUNNING') AND deleted=0")
    List<UUID> active();
    @Insert("""
        INSERT INTO research_task(id,owner_id,knowledge_base_id,client_request_id,request_hash,status,revision,execution_id,checkpoint,created_at,updated_at)
        VALUES(#{id},#{ownerId},#{knowledgeBaseId},#{clientRequestId},#{requestHash},#{status},#{revision},#{executionId},CAST(#{checkpoint} AS jsonb),#{createdAt},#{updatedAt})
        """)
    int create(TaskEntity task);
    @Update("""
        UPDATE research_task SET status=#{status},revision=#{revision},execution_id=#{executionId},
          checkpoint=CAST(#{checkpoint} AS jsonb),updated_at=#{updatedAt},deleted=#{deleted} WHERE id=#{id}
        """)
    int save(TaskEntity task);
    @Insert("""
        INSERT INTO research_task_event(id,task_id,revision,kind,status,checkpoint)
        VALUES(#{event},#{task.id},#{task.revision},#{kind},#{task.status},CAST(#{task.checkpoint} AS jsonb))
        """)
    int event(@Param("event") UUID event, @Param("task") TaskEntity task, @Param("kind") String kind);
    @Select("SELECT id,revision,kind,status,checkpoint,created_at FROM research_task_event WHERE task_id=#{id} AND deleted=0 ORDER BY revision DESC LIMIT 101")
    List<TaskRequests.Event> events(UUID id);
}
