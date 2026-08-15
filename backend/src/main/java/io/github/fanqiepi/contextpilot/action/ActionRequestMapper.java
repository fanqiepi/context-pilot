package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActionRequestMapper {

    String SELECT_COLUMNS = """
            id, conversation_id, user_message_id, assistant_message_id,
            capability_id, capability_version, action_type,
            parameters::text AS parameters_json,
            target_document_id, health_issue_id,
            display_summary, status, result_summary, error_summary, trace_id,
            expires_at, confirmed_at, executed_at, created_at, updated_at, deleted
            """;

    @Insert("""
            INSERT INTO action_request (
                id, conversation_id, user_message_id, assistant_message_id,
                capability_id, capability_version, action_type, parameters,
                target_document_id, health_issue_id,
                display_summary, status, trace_id, expires_at,
                created_at, updated_at, deleted
            ) VALUES (
                #{id}, #{conversationId}, #{userMessageId}, #{assistantMessageId},
                #{capabilityId}, #{capabilityVersion}, #{actionType},
                CAST(#{parametersJson} AS jsonb),
                #{targetDocumentId}, #{healthIssueId},
                #{displaySummary}, #{status}, #{traceId}, #{expiresAt},
                #{createdAt}, #{updatedAt}, #{deleted}
            )
            """)
    int insert(ActionRequestEntity entity);

    @Select("SELECT " + SELECT_COLUMNS + " FROM action_request WHERE id = #{id} AND deleted = 0")
    ActionRequestEntity selectById(UUID id);

    @Select({
            "<script>",
            "SELECT " + SELECT_COLUMNS,
            "FROM action_request",
            "WHERE deleted = 0 AND assistant_message_id IN",
            "<foreach collection='assistantMessageIds' item='messageId' open='(' separator=',' close=')'>",
            "#{messageId}",
            "</foreach>",
            "</script>"
    })
    List<ActionRequestEntity> selectByAssistantMessageIds(
            @Param("assistantMessageIds") List<UUID> assistantMessageIds);

    @Update("""
            UPDATE action_request
            SET status = 'EXPIRED', updated_at = #{now}
            WHERE id = #{id}
              AND deleted = 0
              AND status = 'PENDING_CONFIRMATION'
              AND expires_at <= #{now}
            """)
    int expire(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE action_request
            SET status = 'EXECUTING', confirmed_at = #{now}, updated_at = #{now}
            WHERE id = #{id}
              AND deleted = 0
              AND status = 'PENDING_CONFIRMATION'
              AND expires_at > #{now}
            """)
    int claimExecution(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE action_request
            SET status = 'SUCCEEDED', result_summary = #{resultSummary},
                error_summary = NULL, executed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND status = 'EXECUTING'
            """)
    int completeSuccess(
            @Param("id") UUID id,
            @Param("resultSummary") String resultSummary,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE action_request
            SET status = 'FAILED', error_summary = #{errorSummary},
                result_summary = NULL, executed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0 AND status = 'EXECUTING'
            """)
    int completeFailure(
            @Param("id") UUID id,
            @Param("errorSummary") String errorSummary,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE action_request
            SET status = 'REJECTED', updated_at = #{now}
            WHERE id = #{id}
              AND deleted = 0
              AND status = 'PENDING_CONFIRMATION'
              AND expires_at > #{now}
            """)
    int reject(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
