package io.github.fanqiepi.contextpilot.projectsource;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProjectPreviewMapper extends BaseMapper<ProjectPreviewEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    String lockKey(String key);
    @Select("SELECT * FROM project_source_preview WHERE owner_id=#{owner} AND client_request_id=#{key}")
    ProjectPreviewEntity byKey(@Param("owner") UUID owner, @Param("key") UUID key);
    @Select("SELECT * FROM project_source_preview WHERE id=#{id} AND owner_id=#{owner} AND deleted=0 FOR UPDATE")
    ProjectPreviewEntity lockOwned(@Param("owner") UUID owner, @Param("id") UUID id);
    @Update("UPDATE project_source_preview SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='PENDING' AND deleted=0 AND expires_at>CURRENT_TIMESTAMP")
    int claim(UUID id);
    @Update("UPDATE project_source_preview SET status='FAILED',error_code=#{code},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status IN ('PENDING','RUNNING') AND deleted=0")
    int fail(@Param("id") UUID id, @Param("code") String code);
    @Update("UPDATE project_source_preview SET status='FAILED',error_code=#{code},error_path=#{path},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status IN ('PENDING','RUNNING') AND deleted=0")
    int failPath(@Param("id") UUID id, @Param("code") String code, @Param("path") String path);
    @Update("UPDATE project_source_preview SET status='FAILED',error_code='SOURCE_PROCESS_RESTARTED',updated_at=CURRENT_TIMESTAMP WHERE status IN ('PENDING','RUNNING') AND deleted=0")
    int recover();
    record Item(UUID id, UUID previewId, String path, String mediaType, int byteCount, String sha256,
            String snapshotStorageKey, OffsetDateTime fetchedAt, int deleted) { }
    @Select("SELECT * FROM project_source_preview_item WHERE preview_id=#{id} AND deleted=0 ORDER BY path")
    List<Item> items(UUID id);
    @Insert("""
        INSERT INTO project_source_preview_item(id,preview_id,path,media_type,byte_count,sha256,snapshot_storage_key,fetched_at)
        VALUES(#{id},#{previewId},#{path},#{mediaType},#{byteCount},#{sha256},#{snapshotStorageKey},#{fetchedAt})
        """)
    int insertItem(Item item);
    @Update("""
        UPDATE project_source_preview SET status='READY',resolved_commit=#{commit},updated_at=CURRENT_TIMESTAMP
        WHERE id=#{id} AND status='RUNNING' AND deleted=0 AND expires_at>CURRENT_TIMESTAMP
        """)
    int ready(@Param("id") UUID id, @Param("commit") String commit);
}
