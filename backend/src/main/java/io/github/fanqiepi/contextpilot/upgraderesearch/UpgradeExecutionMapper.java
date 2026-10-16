package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.time.OffsetDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UpgradeExecutionMapper {
    @Select("SELECT ordinal,tool_name AS \"toolName\",parameter_summary AS \"parameterSummary\",status,cache_hit AS \"cacheHit\",error_code AS \"errorCode\",started_at AS \"startedAt\",finished_at AS \"finishedAt\" FROM upgrade_research_tool_call WHERE run_id=#{id} AND deleted=0 ORDER BY ordinal")
    List<Map<String,Object>> toolSummaries(UUID id);
    @Select("SELECT ordinal,provider,model,status,input_tokens AS \"inputTokens\",output_tokens AS \"outputTokens\",total_tokens AS \"totalTokens\",latency_ms AS \"latencyMs\",error_code AS \"errorCode\" FROM upgrade_research_model_call WHERE run_id=#{id} AND deleted=0 ORDER BY ordinal")
    List<Map<String,Object>> modelSummaries(UUID id);
    @Update("""
        UPDATE upgrade_research_run SET model_calls=model_calls+#{models},tool_requests=tool_requests+#{tools},
          retrieval_calls=retrieval_calls+#{retrievals},http_calls=http_calls+#{http},revision=revision+1,updated_at=#{now}
        WHERE id=#{id} AND status='RUNNING' AND deleted=0
        """)
    int count(@Param("id") UUID id,@Param("models") int models,@Param("tools") int tools,
            @Param("retrievals") int retrievals,@Param("http") int http,@Param("now") OffsetDateTime now);

    @Insert("""
        INSERT INTO upgrade_research_model_call(id,run_id,ordinal,provider,model,prompt_version,schema_version,trace_id,status,started_at)
        VALUES(#{id},#{run.id},#{ordinal},'DEEPSEEK','deepseek-v4-flash',#{run.promptVersion},#{run.schemaVersion},#{run.traceId},'STARTED',#{now})
        """)
    int beginModel(@Param("id") UUID id,@Param("run") UpgradeRunEntity run,@Param("ordinal") int ordinal,@Param("now") OffsetDateTime now);

    @Update("""
        UPDATE upgrade_research_model_call SET status='SUCCEEDED',model=#{model},input_tokens=#{input},output_tokens=#{output},
          total_tokens=#{total},latency_ms=#{latency},finished_at=#{now} WHERE id=#{id} AND status='STARTED'
        """)
    int finishModel(@Param("id") UUID id,@Param("model") String model,@Param("input") Integer input,
            @Param("output") Integer output,@Param("total") Integer total,@Param("latency") long latency,@Param("now") OffsetDateTime now);

    @Insert("""
        INSERT INTO upgrade_research_tool_call(id,run_id,ordinal,model_call_id,provider_tool_call_id,tool_name,parameter_hash,
          parameter_summary,status,cache_hit,started_at)
        VALUES(#{id},#{run},#{ordinal},#{model},#{call},#{name},#{hash},#{summary},'STARTED',#{cache},#{now})
        """)
    int beginTool(@Param("id") UUID id,@Param("run") UUID run,@Param("ordinal") int ordinal,@Param("model") UUID model,
            @Param("call") String call,@Param("name") String name,@Param("hash") String hash,@Param("summary") String summary,
            @Param("cache") boolean cache,@Param("now") OffsetDateTime now);

    @Update("""
        UPDATE upgrade_research_tool_call SET status=#{status},error_code=#{error},finished_at=#{now}
        WHERE id=#{id} AND status='STARTED'
        """)
    int finishTool(@Param("id") UUID id,@Param("status") String status,@Param("error") String error,@Param("now") OffsetDateTime now);

    @Insert("""
        INSERT INTO upgrade_research_source(id,run_id,source_id,source_type,document_id,chunk_id,profile_id,content_hash,
          release_id,release_tag,source_url,published_at,fetched_at,excerpt,truncated,prerelease)
        VALUES(#{id},#{runId},#{sourceId},#{sourceType},#{documentId},#{chunkId},#{profileId},#{contentHash},
          #{releaseId},#{releaseTag},#{sourceUrl},#{publishedAt},#{fetchedAt},#{excerpt},#{truncated},#{prerelease})
        """)
    int source(UpgradeEvidence evidence);

    @Select("""
        SELECT id,run_id,source_id,source_type,document_id,chunk_id,profile_id,content_hash,release_id,release_tag,
          source_url,published_at,fetched_at,excerpt,truncated,prerelease
        FROM upgrade_research_source WHERE run_id=#{id} AND deleted=0 ORDER BY source_id
        """)
    List<UpgradeEvidence> sources(UUID id);

    @Update("""
        UPDATE upgrade_research_run SET status=#{status},draft=#{draft},draft_hash=#{hash},gaps=CAST(#{gaps} AS jsonb),
          revision=revision+1,updated_at=#{now} WHERE id=#{id} AND status='RUNNING' AND deleted=0
        """)
    int finish(@Param("id") UUID id,@Param("status") UpgradeRunStatus status,@Param("draft") String draft,
            @Param("hash") String hash,@Param("gaps") String gaps,@Param("now") OffsetDateTime now);
}
