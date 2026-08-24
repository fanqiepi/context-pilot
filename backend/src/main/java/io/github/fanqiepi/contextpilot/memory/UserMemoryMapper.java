package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMemoryMapper {

    String SELECT_COLUMNS = "id, owner_user_id, memory_type, scope_type, status, current_revision, "
            + "content_hash, expires_at, version, created_at, updated_at, deleted";

    @Insert("""
            INSERT INTO user_memory (
                id, owner_user_id, memory_type, scope_type, status, current_revision,
                content_hash, expires_at, version, created_at, updated_at, deleted
            ) VALUES (
                #{id}, #{ownerUserId}, #{memoryType}, #{scopeType}, #{status}, #{currentRevision},
                #{contentHash}, #{expiresAt}, #{version}, #{createdAt}, #{updatedAt}, #{deleted}
            )
            """)
    int insert(UserMemoryEntity entity);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_memory "
            + "WHERE id = #{id} AND owner_user_id = #{ownerUserId} AND deleted = 0")
    UserMemoryEntity selectOwnedById(@Param("ownerUserId") UUID ownerUserId, @Param("id") UUID id);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_memory "
            + "WHERE owner_user_id = #{ownerUserId} AND memory_type = 'RESPONSE_PREFERENCE' "
            + "AND scope_type = 'GLOBAL' AND status = 'ACTIVE' AND content_hash = #{contentHash} "
            + "AND deleted = 0 AND (expires_at IS NULL OR expires_at > #{now}) "
            + "ORDER BY updated_at DESC, id DESC LIMIT 1")
    UserMemoryEntity selectActiveByHash(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("contentHash") String contentHash,
            @Param("now") OffsetDateTime now);

    @Select("SELECT COUNT(*) FROM user_memory WHERE owner_user_id = #{ownerUserId} AND deleted = 0")
    long countUndeleted(UUID ownerUserId);

    @Select("SELECT COUNT(*) FROM user_memory WHERE owner_user_id = #{ownerUserId} "
            + "AND status = 'ACTIVE' AND deleted = 0 "
            + "AND (expires_at IS NULL OR expires_at > #{now})")
    long countActive(@Param("ownerUserId") UUID ownerUserId, @Param("now") OffsetDateTime now);
}
