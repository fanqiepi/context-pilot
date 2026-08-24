package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMemoryRevisionMapper {

    String SELECT_COLUMNS = "revision.id AS id, revision.memory_id AS memory_id, "
            + "revision.revision_number AS revision_number, revision.content AS content, "
            + "revision.content_hash AS content_hash, revision.source_type AS source_type, "
            + "revision.source_conversation_id AS source_conversation_id, "
            + "revision.source_message_id AS source_message_id, "
            + "revision.change_reason AS change_reason, revision.created_at AS created_at, "
            + "revision.deleted AS deleted";

    @Insert("""
            INSERT INTO user_memory_revision (
                id, memory_id, revision_number, content, content_hash, source_type,
                source_conversation_id, source_message_id, change_reason, created_at, deleted
            ) VALUES (
                #{id}, #{memoryId}, #{revisionNumber}, #{content}, #{contentHash}, #{sourceType},
                #{sourceConversationId}, #{sourceMessageId}, #{changeReason}, #{createdAt}, #{deleted}
            )
            """)
    int insert(UserMemoryRevisionEntity entity);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_memory_revision revision "
            + "JOIN user_memory memory ON memory.id = revision.memory_id "
            + "WHERE revision.memory_id = #{memoryId} AND revision.revision_number = #{revisionNumber} "
            + "AND memory.owner_user_id = #{ownerUserId} "
            + "AND revision.deleted = 0 AND memory.deleted = 0")
    UserMemoryRevisionEntity selectOwnedRevision(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("memoryId") UUID memoryId,
            @Param("revisionNumber") int revisionNumber);
}
