package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
            + "WHERE id = #{id} AND owner_user_id = #{ownerUserId}")
    UserMemoryEntity selectOwnedByIdIncludingDeleted(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("id") UUID id);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_memory "
            + "WHERE owner_user_id = #{ownerUserId} AND deleted = 0 "
            + "ORDER BY updated_at DESC, id DESC")
    List<UserMemoryEntity> selectOwnedAll(UUID ownerUserId);

    @Select("SELECT memory.id AS memory_id, revision.id AS revision_id, "
            + "revision.revision_number, revision.content "
            + "FROM user_memory memory "
            + "JOIN user_memory_revision revision ON revision.memory_id = memory.id "
            + "AND revision.revision_number = memory.current_revision "
            + "WHERE memory.owner_user_id = #{ownerUserId} "
            + "AND memory.memory_type = 'RESPONSE_PREFERENCE' AND memory.scope_type = 'GLOBAL' "
            + "AND memory.status = 'ACTIVE' AND memory.deleted = 0 AND revision.deleted = 0 "
            + "AND (memory.expires_at IS NULL OR memory.expires_at > #{now}) "
            + "ORDER BY memory.updated_at DESC, memory.id DESC")
    List<UserMemoryContextCandidate> selectActiveContextCandidates(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("now") OffsetDateTime now);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_memory "
            + "WHERE owner_user_id = #{ownerUserId} AND memory_type = 'RESPONSE_PREFERENCE' "
            + "AND scope_type = 'GLOBAL' AND status = 'ACTIVE' AND content_hash = #{contentHash} "
            + "AND deleted = 0 AND (expires_at IS NULL OR expires_at > #{now}) "
            + "ORDER BY updated_at DESC, id DESC LIMIT 1")
    UserMemoryEntity selectActiveByHash(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("contentHash") String contentHash,
            @Param("now") OffsetDateTime now);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_memory "
            + "WHERE owner_user_id = #{ownerUserId} AND status = 'ACTIVE' "
            + "AND content_hash = #{contentHash} AND id <> #{excludedId} AND deleted = 0 "
            + "ORDER BY updated_at DESC, id DESC LIMIT 1")
    UserMemoryEntity selectStatusActiveByHashExcludingId(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("contentHash") String contentHash,
            @Param("excludedId") UUID excludedId);

    @Update("""
            UPDATE user_memory
            SET status = #{status}, current_revision = #{currentRevision},
                content_hash = #{contentHash}, expires_at = #{expiresAt},
                version = version + 1, updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_user_id = #{ownerUserId}
              AND version = #{expectedVersion} AND deleted = 0
            """)
    int updateOwnedWithVersion(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") UserMemoryStatus status,
            @Param("currentRevision") int currentRevision,
            @Param("contentHash") String contentHash,
            @Param("expiresAt") OffsetDateTime expiresAt,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update("""
            UPDATE user_memory
            SET deleted = 1, version = version + 1, updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_user_id = #{ownerUserId} AND deleted = 0
            """)
    int logicalDeleteOwned(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("id") UUID id,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Select("SELECT COUNT(*) FROM user_memory WHERE owner_user_id = #{ownerUserId} AND deleted = 0")
    long countUndeleted(UUID ownerUserId);

    @Select("SELECT COUNT(*) FROM user_memory WHERE owner_user_id = #{ownerUserId} "
            + "AND status = 'ACTIVE' AND deleted = 0 "
            + "AND (expires_at IS NULL OR expires_at > #{now})")
    long countActive(@Param("ownerUserId") UUID ownerUserId, @Param("now") OffsetDateTime now);
}
