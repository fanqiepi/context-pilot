package io.github.fanqiepi.contextpilot.book;

import java.util.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BookMapper extends BaseMapper<BookImportEntity> {
    String BOOK_COLUMNS = "b.id,b.owner_id,b.title,b.source_description,b.latest_version_id,b.revision,b.created_at,b.deleted";
    String VERSION_COLUMNS = "id,owner_id,book_id,file_hash,text_hash,parser_id,parser_version,options_hash,chapter_count,paragraph_count,character_count,created_at,deleted";
    String CHAPTER_COLUMNS = "id,version_id,chapter_ordinal,title,first_paragraph_ordinal,last_paragraph_ordinal,text_hash";
    String PARAGRAPH_COLUMNS = "id,version_id,chapter_id,paragraph_ordinal,chapter_paragraph_ordinal,text,code_point_count,text_hash";
    @Select("SELECT id FROM app_user WHERE id=#{owner} AND deleted=0 FOR UPDATE")
    @Options(timeout=3)
    UUID serialize(UUID owner);

    @Select("SELECT * FROM book_import WHERE owner_id=#{owner} AND client_request_id=#{key}")
    @Options(timeout=3)
    BookImportEntity byRequest(@Param("owner") UUID owner, @Param("key") UUID key);

    @Select("SELECT * FROM book_import WHERE id=#{id} AND owner_id=#{owner} AND deleted=0")
    @Options(timeout=3)
    BookImportEntity imported(@Param("owner") UUID owner, @Param("id") UUID id);

    @Select("SELECT count(*) FROM book_import WHERE owner_id=#{owner} AND status IN ('PARSING','PREVIEW_READY') AND expires_at>CURRENT_TIMESTAMP AND deleted=0")
    @Options(timeout=3)
    int previews(UUID owner);
    @Select("SELECT coalesce(sum(file_bytes),0) FROM book_import WHERE owner_id=#{owner} AND owns_file=true")
    @Options(timeout=3)
    long reservedBytes(UUID owner);
    @Update("UPDATE book_import SET status='FAILED',error_code='PARSE_INTERRUPTED',updated_at=CURRENT_TIMESTAMP WHERE status='PARSING'")
    @Options(timeout=3)
    int recover();

    @Insert("""
        INSERT INTO book_import(id,owner_id,client_request_id,request_hash,original_filename,storage_key,file_hash,file_bytes,
        owns_file,title,source_description,parser_id,heading_level,status,expires_at,target_book_id)
        VALUES(#{id},#{ownerId},#{clientRequestId},#{requestHash},#{originalFilename},#{storageKey},#{fileHash},#{fileBytes},
        #{ownsFile},#{title},#{sourceDescription},#{parserId},#{headingLevel},#{status},#{expiresAt},#{targetBookId})
        """)
    @Options(timeout=3)
    int createImport(BookImportEntity row);
    @Update("""
        UPDATE book_import SET status=#{status},preview_hash=#{previewHash},preview_json=CAST(#{previewJson} AS jsonb),
        result_version_id=#{resultVersionId},error_code=#{errorCode},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}
        """)
    @Options(timeout=3)
    int saveImport(BookImportEntity row);

    @Select("SELECT "+BOOK_COLUMNS+",b.latest_version_id AS reading_version_id,NULL::timestamptz AS last_read_at FROM reading_book b WHERE b.id=#{id} AND b.owner_id=#{owner}")
    @Options(timeout=3)
    BookRows.Book book(@Param("owner") UUID owner, @Param("id") UUID id);
    @Select("SELECT "+BOOK_COLUMNS+"""
        ,coalesce(s.version_id,b.latest_version_id) AS reading_version_id,s.last_read_at
        FROM reading_book b LEFT JOIN LATERAL
          (SELECT version_id,last_read_at FROM reading_state WHERE book_id=b.id AND owner_id=b.owner_id AND deleted=0
             AND last_read_at IS NOT NULL ORDER BY last_read_at DESC,id LIMIT 1) s ON true
        WHERE b.owner_id=#{owner} AND b.deleted=0
        ORDER BY s.last_read_at DESC NULLS LAST,b.created_at DESC,b.id LIMIT #{limit} OFFSET #{offset}
        """)
    @Options(timeout=3)
    List<BookRows.Book> books(@Param("owner") UUID owner, @Param("limit") int limit, @Param("offset") long offset);
    @Select("SELECT count(*) FROM reading_book WHERE owner_id=#{owner} AND deleted=0")
    @Options(timeout=3)
    int bookCount(UUID owner);
    @Select("SELECT count(*) FROM book_version WHERE book_id=#{id}")
    @Options(timeout=3)
    int versionCount(UUID id);
    @Insert("INSERT INTO reading_book(id,owner_id,title,source_description) VALUES(#{id},#{ownerId},#{title},#{sourceDescription})")
    @Options(timeout=3)
    int createBook(BookRows.Book book);
    @Update("UPDATE reading_book SET latest_version_id=#{version},revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{book}")
    @Options(timeout=3)
    int latest(@Param("book") UUID book, @Param("version") UUID version);
    @Update("UPDATE reading_book SET title=#{title},source_description=#{source},revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    @Options(timeout=3)
    int editBook(@Param("id") UUID id, @Param("title") String title, @Param("source") String source);
    @Update("UPDATE reading_book SET deleted=1,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    @Options(timeout=3)
    int removeBook(UUID id);

    @Select("SELECT "+VERSION_COLUMNS+" FROM book_version WHERE owner_id=#{owner} AND file_hash=#{hash} AND parser_id=#{parser} AND parser_version=1 AND options_hash=#{options}")
    @Options(timeout=3)
    BookRows.Version duplicate(@Param("owner") UUID owner, @Param("hash") String hash, @Param("parser") String parser, @Param("options") String options);
    @Select("""
        SELECT v.id,v.owner_id,v.book_id,v.file_hash,v.text_hash,v.parser_id,v.parser_version,v.options_hash,
          v.chapter_count,v.paragraph_count,v.character_count,v.created_at,v.deleted
        FROM book_version v JOIN reading_book b ON b.id=v.book_id
        WHERE v.id=#{id} AND v.owner_id=#{owner} AND b.owner_id=#{owner} AND b.deleted=0 AND v.deleted=0 AND v.status='READY'
        """)
    @Options(timeout=3)
    BookRows.Version version(@Param("owner") UUID owner, @Param("id") UUID id);
    @Select("SELECT "+VERSION_COLUMNS+" FROM book_version WHERE book_id=#{book} AND deleted=0 AND status='READY' ORDER BY created_at DESC,id")
    @Options(timeout=3)
    List<BookRows.Version> versions(UUID book);
    @Insert("""
        INSERT INTO book_version(id,owner_id,book_id,source_import_id,file_hash,text_hash,parser_id,parser_version,options_hash,
        status,chapter_count,paragraph_count,character_count)
        VALUES(#{v.id},#{v.ownerId},#{v.bookId},#{source},#{v.fileHash},#{v.textHash},#{v.parserId},1,#{v.optionsHash},
        'READY',#{v.chapterCount},#{v.paragraphCount},#{v.characterCount})
        """)
    @Options(timeout=3)
    int createVersion(@Param("v") BookRows.Version version, @Param("source") UUID source);
    @Insert("""
        <script>INSERT INTO book_chapter(id,version_id,chapter_ordinal,title,first_paragraph_ordinal,last_paragraph_ordinal,text_hash) VALUES
        <foreach collection="rows" item="c" separator=",">(#{c.id},#{c.versionId},#{c.chapterOrdinal},#{c.title},#{c.firstParagraphOrdinal},#{c.lastParagraphOrdinal},#{c.textHash})</foreach></script>
        """)
    @Options(timeout=3)
    int chapters(@Param("rows") List<BookRows.Chapter> rows);
    @Insert("""
        <script>INSERT INTO book_paragraph(id,version_id,chapter_id,paragraph_ordinal,chapter_paragraph_ordinal,text,code_point_count,text_hash) VALUES
        <foreach collection="rows" item="p" separator=",">(#{p.id},#{p.versionId},#{p.chapterId},#{p.paragraphOrdinal},#{p.chapterParagraphOrdinal},#{p.text},#{p.codePointCount},#{p.textHash})</foreach></script>
        """)
    @Options(timeout=3)
    int paragraphs(@Param("rows") List<BookRows.Paragraph> rows);
    @Select("SELECT "+CHAPTER_COLUMNS+" FROM book_chapter WHERE version_id=#{version} AND deleted=0 ORDER BY chapter_ordinal LIMIT #{limit} OFFSET #{offset}")
    @Options(timeout=3)
    List<BookRows.Chapter> chapterPage(@Param("version") UUID version, @Param("limit") int limit, @Param("offset") long offset);
    @Select("SELECT "+CHAPTER_COLUMNS+" FROM book_chapter WHERE id=#{id} AND version_id=#{version} AND deleted=0")
    @Options(timeout=3)
    BookRows.Chapter chapter(@Param("version") UUID version, @Param("id") UUID id);
    @Select("SELECT "+PARAGRAPH_COLUMNS+" FROM book_paragraph WHERE version_id=#{version} AND chapter_id=#{chapter} AND paragraph_ordinal>#{after} AND deleted=0 ORDER BY paragraph_ordinal LIMIT #{limit}")
    @Options(timeout=3)
    List<BookRows.Paragraph> paragraphPage(@Param("version") UUID version, @Param("chapter") UUID chapter, @Param("after") int after, @Param("limit") int limit);
    @Select("SELECT "+PARAGRAPH_COLUMNS+" FROM book_paragraph WHERE id=#{id} AND version_id=#{version} AND deleted=0")
    @Options(timeout=3)
    BookRows.Paragraph paragraph(@Param("version") UUID version, @Param("id") UUID id);
}
