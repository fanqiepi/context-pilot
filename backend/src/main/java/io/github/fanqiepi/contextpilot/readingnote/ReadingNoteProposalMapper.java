package io.github.fanqiepi.contextpilot.readingnote;

import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReadingNoteProposalMapper {
    @Select("SELECT * FROM reading_note_proposal WHERE id=#{id} AND owner_id=#{owner} AND deleted=0")
    @Options(timeout=3)
    ReadingNoteProposal owned(@Param("owner") UUID owner,@Param("id") UUID id);

    @Select("SELECT * FROM reading_note_proposal WHERE owner_id=#{owner} AND client_request_id=#{key}")
    @Options(timeout=3)
    ReadingNoteProposal replay(@Param("owner") UUID owner,@Param("key") UUID key);

    @Select("SELECT * FROM reading_note_proposal WHERE owner_id=#{owner} AND task_id=#{task} AND deleted=0 ORDER BY created_at DESC,id DESC LIMIT 1")
    @Options(timeout=3)
    ReadingNoteProposal latest(@Param("owner") UUID owner,@Param("task") UUID task);

    @Insert("""
        INSERT INTO reading_note_proposal(id,owner_id,book_id,version_id,task_id,client_request_id,request_hash,
          kind,title,body,citations_json,required_boundary,content_hash,scope_epoch,user_edited,status,expires_at,supersedes_id,trace_id)
        VALUES(#{id},#{ownerId},#{bookId},#{versionId},#{taskId},#{clientRequestId},#{requestHash},
          #{kind},#{title},#{body},CAST(#{citationsJson} AS jsonb),#{requiredBoundary},#{contentHash},#{scopeEpoch},
          #{userEdited},#{status},#{expiresAt},#{supersedesId},#{traceId})
        """)
    @Options(timeout=3)
    int create(ReadingNoteProposal row);

    @Update("""
        UPDATE reading_note_proposal SET status=#{status},error_code=#{errorCode},result_note_id=#{resultNoteId},
          revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=0
        """)
    @Options(timeout=3)
    int transition(ReadingNoteProposal row);
}
