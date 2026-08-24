package io.github.fanqiepi.contextpilot.memory;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMemoryContextMapper {

    @Insert("""
            INSERT INTO message_memory_context (
                id, assistant_message_id, memory_id, memory_revision_id,
                display_order, context_character_count, context_truncated,
                created_at, deleted
            ) VALUES (
                #{id}, #{assistantMessageId}, #{memoryId}, #{memoryRevisionId},
                #{displayOrder}, #{contextCharacterCount}, #{contextTruncated},
                #{createdAt}, #{deleted}
            )
            """)
    int insert(MessageMemoryContextEntity entity);

    @Select({"<script>",
            "SELECT context.assistant_message_id, context.memory_id, revision.revision_number,",
            "context.context_character_count, context.context_truncated,",
            "CASE WHEN memory.deleted = 0 THEN revision.content ELSE NULL END AS content",
            "FROM message_memory_context context",
            "JOIN user_memory memory ON memory.id = context.memory_id",
            "JOIN user_memory_revision revision ON revision.id = context.memory_revision_id",
            "WHERE context.deleted = 0 AND context.assistant_message_id IN",
            "<foreach collection='messageIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY context.assistant_message_id, context.display_order",
            "</script>"})
    List<MessageMemoryContextHistoryRow> selectHistoryByAssistantMessageIds(
            @Param("messageIds") List<UUID> messageIds);
}
