package io.github.fanqiepi.contextpilot.readingstate;

import java.util.UUID;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReadingStateMapper extends BaseMapper<ReadingState> {
    @Insert("""
        INSERT INTO reading_state(id,owner_id,book_id,version_id) VALUES(#{id},#{owner},#{book},#{version})
        ON CONFLICT(owner_id,version_id) DO NOTHING
        """)
    @Options(timeout=3)
    int initialize(@Param("id") UUID id,@Param("owner") UUID owner,@Param("book") UUID book,@Param("version") UUID version);
    @Select("SELECT * FROM reading_state WHERE owner_id=#{owner} AND version_id=#{version} AND deleted=0")
    @Options(timeout=3)
    ReadingState state(@Param("owner") UUID owner,@Param("version") UUID version);
    @Select("SELECT * FROM reading_state WHERE owner_id=#{owner} AND version_id=#{version} AND deleted=0 FOR UPDATE")
    @Options(timeout=3)
    ReadingState lock(@Param("owner") UUID owner,@Param("version") UUID version);
    @Update("""
        UPDATE reading_state SET resume_paragraph_id=#{resumeParagraphId},resume_offset=#{resumeOffset},
          resume_revision=#{resumeRevision},last_read_at=#{lastReadAt},boundary_ordinal=#{boundaryOrdinal},
          scope_revision=#{scopeRevision},visibility_epoch=#{visibilityEpoch},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}
        """)
    @Options(timeout=3)
    int save(ReadingState state);
    record Command(UUID versionId,String commandType,String requestHash,long resultRevision) {}
    @Select("SELECT version_id,command_type,request_hash,result_revision FROM reading_state_command WHERE owner_id=#{owner} AND client_request_id=#{key}")
    @Options(timeout=3)
    Command command(@Param("owner") UUID owner,@Param("key") UUID key);
    @Insert("""
        INSERT INTO reading_state_command(id,owner_id,version_id,client_request_id,command_type,request_hash,result_revision)
        VALUES(#{id},#{owner},#{version},#{key},#{type},#{hash},#{revision})
        """)
    @Options(timeout=3)
    int commandResult(@Param("id") UUID id,@Param("owner") UUID owner,@Param("version") UUID version,@Param("key") UUID key,
                      @Param("type") String type,@Param("hash") String hash,@Param("revision") long revision);
}
