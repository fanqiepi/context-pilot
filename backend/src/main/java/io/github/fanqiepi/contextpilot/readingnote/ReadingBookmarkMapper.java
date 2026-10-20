package io.github.fanqiepi.contextpilot.readingnote;

import java.util.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReadingBookmarkMapper extends BaseMapper<ReadingBookmark> {
    @Insert("""
        INSERT INTO reading_bookmark(id,owner_id,book_id,version_id,client_request_id,request_hash,paragraph_id,position_offset,label,required_boundary)
        VALUES(#{id},#{ownerId},#{bookId},#{versionId},#{clientRequestId},#{requestHash},#{paragraphId},#{positionOffset},#{label},#{requiredBoundary})
        """)
    @Options(timeout=3)
    int create(ReadingBookmark bookmark);
    @Update("UPDATE reading_bookmark SET deleted=1,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=0")
    @Options(timeout=3)
    int remove(UUID id);
    @Select("SELECT * FROM reading_bookmark WHERE owner_id=#{owner} AND client_request_id=#{key}")
    @Options(timeout=3)
    ReadingBookmark byRequest(@Param("owner") UUID owner,@Param("key") UUID key);
    @Select("SELECT * FROM reading_bookmark WHERE owner_id=#{owner} AND version_id=#{version} AND paragraph_id=#{paragraph} AND position_offset=#{offset} AND deleted=0")
    @Options(timeout=3)
    ReadingBookmark at(@Param("owner") UUID owner,@Param("version") UUID version,@Param("paragraph") UUID paragraph,@Param("offset") int offset);
    @Select("SELECT * FROM reading_bookmark WHERE id=#{id} AND owner_id=#{owner}")
    @Options(timeout=3)
    ReadingBookmark owned(@Param("owner") UUID owner,@Param("id") UUID id);
    @Select("SELECT * FROM reading_bookmark WHERE owner_id=#{owner} AND version_id=#{version} AND deleted=0 ORDER BY created_at DESC,id LIMIT #{limit} OFFSET #{offset}")
    @Options(timeout=3)
    List<ReadingBookmark> list(@Param("owner") UUID owner,@Param("version") UUID version,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT count(*) FROM reading_bookmark WHERE owner_id=#{owner} AND version_id=#{version} AND deleted=0")
    @Options(timeout=3)
    long count(@Param("owner") UUID owner,@Param("version") UUID version);
}
