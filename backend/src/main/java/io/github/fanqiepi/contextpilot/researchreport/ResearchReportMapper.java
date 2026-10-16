package io.github.fanqiepi.contextpilot.researchreport;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ResearchReportMapper {
    String COLUMNS="id,owner_id,knowledge_base_id,run_id,source_action_id,title,body,draft_hash,sources::text AS sources,gaps::text AS gaps,created_at,deleted";
    @Select("SELECT "+COLUMNS+" FROM research_report WHERE run_id=#{run} AND owner_id=#{owner}")
    ResearchReport byRun(@Param("run") UUID run,@Param("owner") UUID owner);
    @Select("SELECT "+COLUMNS+" FROM research_report WHERE id=#{id} AND owner_id=#{owner} AND deleted=0")
    ResearchReport get(@Param("id") UUID id,@Param("owner") UUID owner);
    @Select("SELECT "+COLUMNS+" FROM research_report WHERE owner_id=#{owner} AND knowledge_base_id=#{kb} AND deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ResearchReport> list(@Param("owner") UUID owner,@Param("kb") UUID kb,@Param("limit") int limit,@Param("offset") long offset);
    @Insert("""
        INSERT INTO research_report(id,owner_id,knowledge_base_id,run_id,source_action_id,title,body,draft_hash,sources,gaps,created_at)
        VALUES(#{id},#{ownerId},#{knowledgeBaseId},#{runId},#{sourceActionId},#{title},#{body},#{draftHash},CAST(#{sources} AS jsonb),CAST(#{gaps} AS jsonb),#{createdAt})
        """)
    int insert(ResearchReport report);
    @Update("UPDATE research_report SET deleted=1 WHERE id=#{id} AND owner_id=#{owner} AND deleted=0")
    int delete(@Param("id") UUID id,@Param("owner") UUID owner);

    record SaveRequest(UUID id,UUID ownerId,UUID clientRequestId,String requestHash,UUID runId,UUID actionRequestId,UUID requestedConversationId){}
    @Select("SELECT id,owner_id,client_request_id,request_hash,run_id,action_request_id,requested_conversation_id FROM research_report_save_request WHERE owner_id=#{owner} AND client_request_id=#{key}")
    SaveRequest request(@Param("owner") UUID owner,@Param("key") UUID key);
    @Insert("""
        INSERT INTO research_report_save_request(id,owner_id,client_request_id,request_hash,run_id,action_request_id,requested_conversation_id)
        VALUES(#{id},#{ownerId},#{clientRequestId},#{requestHash},#{runId},#{actionRequestId},#{requestedConversationId})
        """)
    int saveRequest(SaveRequest request);
    @Select("SELECT id FROM action_request WHERE upgrade_run_id=#{run} AND deleted=0 AND status IN ('PENDING_CONFIRMATION','EXECUTING') ORDER BY created_at DESC LIMIT 1")
    UUID activeAction(UUID run);
    @Select("SELECT requested_conversation_id FROM research_report_save_request WHERE action_request_id=#{id} ORDER BY created_at LIMIT 1")
    UUID requestedConversation(UUID id);
}
