package io.github.fanqiepi.contextpilot.researchreview;

import java.util.*;
import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.fanqiepi.contextpilot.taskworkspace.TaskEntity;

@Mapper
public interface ReviewMapper extends BaseMapper<ReviewEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    String serialize(String key);
    @Select("SELECT * FROM research_task WHERE id=#{id} FOR UPDATE")
    TaskEntity parent(UUID id);
    @Select("SELECT * FROM research_review WHERE owner_id=#{owner} AND client_request_id=#{key}")
    ReviewEntity request(@Param("owner") UUID owner,@Param("key") UUID key);
    @Select("SELECT * FROM research_review WHERE id=#{id} AND deleted=0 FOR UPDATE")
    ReviewEntity lock(UUID id);
    @Select("SELECT COUNT(*) FROM research_review WHERE task_id=#{task} AND snapshot_hash=#{hash}")
    int attempts(@Param("task") UUID task,@Param("hash") String hash);
    @Select("SELECT COUNT(*) FROM research_review WHERE task_id=#{task} AND deleted=0 AND status IN ('QUEUED','RUNNING')")
    int activeForTask(UUID task);
    @Select("SELECT * FROM research_review WHERE task_id=#{task} AND owner_id=#{owner} AND deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<ReviewEntity> list(@Param("task") UUID task,@Param("owner") UUID owner,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT id FROM research_review WHERE deleted=0 AND status IN ('QUEUED','RUNNING')")
    List<UUID> active();
    @Select("SELECT id FROM research_review WHERE task_id=#{task} AND deleted=0 AND status IN ('QUEUED','RUNNING')")
    List<UUID> activeFor(UUID task);
    @Insert("""
        INSERT INTO research_review(id,owner_id,task_id,task_revision,round,client_request_id,request_hash,snapshot_hash,
        snapshot_json,status,execution_id,trace_id,prompt_version,created_at,updated_at)
        VALUES(#{id},#{ownerId},#{taskId},#{taskRevision},#{round},#{clientRequestId},#{requestHash},#{snapshotHash},
        CAST(#{snapshotJson} AS jsonb),#{status},#{executionId},#{traceId},#{promptVersion},#{createdAt},#{updatedAt})
        """)
    int create(ReviewEntity row);
    @Update("""
        UPDATE research_review SET status=#{status},overall_verdict=#{overallVerdict},result_json=CAST(#{resultJson} AS jsonb),
        revision=#{revision},execution_id=#{executionId},error_code=#{errorCode},model=#{model},usage_json=CAST(#{usageJson} AS jsonb),
        started_at=#{startedAt},completed_at=#{completedAt},updated_at=#{updatedAt},deleted=#{deleted} WHERE id=#{id}
        """)
    int save(ReviewEntity row);
}
