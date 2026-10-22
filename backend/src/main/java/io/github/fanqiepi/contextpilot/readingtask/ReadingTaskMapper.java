package io.github.fanqiepi.contextpilot.readingtask;

import java.util.*;
import io.github.fanqiepi.contextpilot.book.BookRows;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReadingTaskMapper {
    @Select("SELECT version()") @Options(timeout=3)
    String databaseVersion();
    // The table choice is a server-owned boolean, never a client/model SQL identifier.
    String TABLE="<choose><when test='index'>reading_index_job</when><otherwise>reading_task</otherwise></choose>";
    @Select("<script>SELECT * FROM "+TABLE+" WHERE id=#{id}</script>") @Options(timeout=3)
    ReadingWork get(@Param("index") boolean index,@Param("id") UUID id);
    @Select("<script>SELECT * FROM "+TABLE+" WHERE owner_id=#{owner} AND client_request_id=#{key}</script>") @Options(timeout=3)
    ReadingWork replay(@Param("index") boolean index,@Param("owner") UUID owner,@Param("key") UUID key);
    @Insert("<script>INSERT INTO "+TABLE+"""
        (id,owner_id,book_id,version_id,client_request_id,request_hash,type,status,execution_id,required_boundary,payload_json,trace_id)
        VALUES(#{w.id},#{w.ownerId},#{w.bookId},#{w.versionId},#{w.clientRequestId},#{w.requestHash},#{w.type},
        #{w.status},#{w.executionId},#{w.requiredBoundary},CAST(#{w.payloadJson} AS jsonb),#{w.traceId})</script>
        """) @Options(timeout=3)
    void insert(@Param("index") boolean index,@Param("w") ReadingWork work);
    @Update("<script>UPDATE "+TABLE+"""
        SET status=#{w.status},revision=#{w.revision},execution_id=#{w.executionId},payload_json=CAST(#{w.payloadJson} AS jsonb),
        error_code=#{w.errorCode},started_at=#{w.startedAt},queued_at=#{w.queuedAt},active_millis=#{w.activeMillis},
        deleted=#{w.deleted},updated_at=CURRENT_TIMESTAMP WHERE id=#{w.id}</script>
        """) @Options(timeout=3)
    void save(@Param("index") boolean index,@Param("w") ReadingWork work);
    @Select("<script>SELECT * FROM "+TABLE+" WHERE owner_id=#{owner} AND version_id=#{version} AND deleted=0 ORDER BY created_at DESC,id LIMIT #{limit} OFFSET #{offset}</script>") @Options(timeout=3)
    List<ReadingWork> list(@Param("index") boolean index,@Param("owner") UUID owner,@Param("version") UUID version,@Param("limit") int limit,@Param("offset") long offset);
    @Select("<script>SELECT * FROM "+TABLE+" WHERE status IN ('QUEUED','RUNNING','PAUSED') AND deleted=0 AND (CAST(#{after} AS uuid) IS NULL OR id>#{after}) ORDER BY id LIMIT 100</script>") @Options(timeout=3)
    List<ReadingWork> active(@Param("index")boolean index,@Param("after")UUID after);
    @Select("SELECT count(*) FROM reading_index_job WHERE owner_id=#{owner} AND version_id=#{version} AND status IN ('QUEUED','RUNNING','PAUSED') AND deleted=0") @Options(timeout=3)
    int activeIndex(@Param("owner") UUID owner,@Param("version") UUID version);
    @Select("SELECT request_hash FROM reading_job_command WHERE owner_id=#{owner} AND client_request_id=#{key}") @Options(timeout=3)
    String commandHash(@Param("owner") UUID owner,@Param("key") UUID key);
    @Insert("""
        INSERT INTO reading_job_command(id,owner_id,task_id,index_job_id,client_request_id,request_hash,command,result_revision)
        VALUES(#{id},#{owner},#{task},#{job},#{key},#{hash},#{command},#{revision})
        """) @Options(timeout=3)
    void command(@Param("id")UUID id,@Param("owner")UUID owner,@Param("task")UUID task,@Param("job")UUID job,
                 @Param("key")UUID key,@Param("hash")String hash,@Param("command")String command,@Param("revision")long revision);
    @Insert("INSERT INTO reading_task_event(id,task_id,index_job_id,revision,status) VALUES(#{id},#{task},#{job},#{revision},#{status})") @Options(timeout=3)
    void event(@Param("id")UUID id,@Param("task")UUID task,@Param("job")UUID job,@Param("revision")long revision,@Param("status")String status);
    @Insert("""
        INSERT INTO reading_ai_call(id,task_id,index_job_id,execution_id,ordinal,phase,status,input_characters,input_bytes,output_token_limit)
        VALUES(#{id},#{task},#{job},#{token},#{ordinal},#{phase},'STARTED',#{chars},#{bytes},#{limit})
        """) @Options(timeout=3)
    void call(@Param("id")UUID id,@Param("task")UUID task,@Param("job")UUID job,@Param("token")UUID token,@Param("ordinal")int ordinal,
              @Param("phase")String phase,@Param("chars")int chars,@Param("bytes")int bytes,@Param("limit")int limit);
    @Update("UPDATE reading_ai_call SET status=#{status},usage_json=CAST(#{usage} AS jsonb),updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status IN ('STARTED','UNKNOWN')") @Options(timeout=3)
    void finishCall(@Param("id")UUID id,@Param("status")String status,@Param("usage")String usage);
    @Select("SELECT * FROM reading_spend_ledger WHERE id=#{id} AND owner_id=#{owner} AND deleted=0 FOR UPDATE") @Options(timeout=3)
    Map<String,Object> ledger(@Param("id")UUID id,@Param("owner")UUID owner);
    @Select("SELECT id FROM reading_spend_ledger WHERE predecessor_id=#{id}") @Options(timeout=3)
    UUID successor(UUID id);
    @Insert("""
        INSERT INTO reading_spend_ledger(id,owner_id,currency,limit_amount,pricing_version,expires_at,approval_digest,call_limit,predecessor_id)
        VALUES(#{id},#{owner},'CNY',#{amount},#{price},#{expires},#{digest},#{calls},#{previous}) ON CONFLICT DO NOTHING
        """) @Options(timeout=3)
    void installSuccessor(@Param("id")UUID id,@Param("owner")UUID owner,@Param("amount")java.math.BigDecimal amount,
                     @Param("price")String price,@Param("expires")java.time.OffsetDateTime expires,@Param("digest")String digest,
                     @Param("calls")int calls,@Param("previous")UUID previous);
    @Insert("""
        INSERT INTO reading_spend_ledger(id,owner_id,currency,limit_amount,pricing_version,expires_at,approval_digest,call_limit)
        VALUES(#{id},#{owner},'CNY',#{amount},#{price},#{expires},#{digest},#{calls}) ON CONFLICT(id) DO NOTHING
        """) @Options(timeout=3)
    void installLive(@Param("id")UUID id,@Param("owner")UUID owner,@Param("amount")java.math.BigDecimal amount,
                     @Param("price")String price,@Param("expires")java.time.OffsetDateTime expires,@Param("digest")String digest,@Param("calls")int calls);
    @Select("SELECT count(*) FROM reading_spend_ledger WHERE id=#{id} AND owner_id=#{owner} AND approval_digest=#{digest} AND deleted=0 AND stop_reason IS NULL AND expires_at>CURRENT_TIMESTAMP") @Options(timeout=3)
    int liveAvailable(@Param("id")UUID id,@Param("owner")UUID owner,@Param("digest")String digest);
    @Update("""
        UPDATE reading_spend_ledger SET reserved_amount=reserved_amount+#{amount},calls_started=calls_started+1,revision=revision+1,updated_at=CURRENT_TIMESTAMP
        WHERE id=#{id} AND deleted=0 AND stop_reason IS NULL AND expires_at>CURRENT_TIMESTAMP
        AND reserved_amount+settled_amount+#{amount}<=limit_amount AND pricing_version=#{version}
        AND (call_limit IS NULL OR calls_started<call_limit)
        """) @Options(timeout=3)
    int reserveSpend(@Param("id")UUID id,@Param("amount")java.math.BigDecimal amount,@Param("version")String version);
    @Update("""
        UPDATE reading_ai_call SET authorization_id=#{authorization},reserved_amount=#{amount},input_token_rate=#{inputRate},output_token_rate=#{outputRate},input_token_bound=#{inputBound}
        WHERE id=#{id}
        """) @Options(timeout=3)
    void priceCall(@Param("id")UUID id,@Param("authorization")UUID authorization,@Param("amount")java.math.BigDecimal amount,
                   @Param("inputRate")java.math.BigDecimal inputRate,@Param("outputRate")java.math.BigDecimal outputRate,@Param("inputBound")int inputBound);
    @Select("SELECT * FROM reading_ai_call WHERE id=#{id}") @Options(timeout=3)
    Map<String,Object> callRow(UUID id);
    @Select("SELECT id FROM reading_ai_call WHERE id=#{id} FOR UPDATE") @Options(timeout=3)
    UUID lockCall(UUID id);
    @Update("""
        UPDATE reading_spend_ledger SET reserved_amount=reserved_amount-#{reserved},settled_amount=settled_amount+#{actual},
        revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}
        """) @Options(timeout=3)
    void settleSpend(@Param("id")UUID id,@Param("reserved")java.math.BigDecimal reserved,@Param("actual")java.math.BigDecimal actual);
    @Update("UPDATE reading_ai_call SET settled_amount=#{amount} WHERE id=#{id} AND settled_amount IS NULL") @Options(timeout=3)
    int settleCall(@Param("id")UUID id,@Param("amount")java.math.BigDecimal amount);
    @Update("UPDATE reading_spend_ledger SET warning_emitted=TRUE,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND warning_emitted=FALSE AND reserved_amount+settled_amount>=limit_amount*0.8") @Options(timeout=3)
    int warnSpend(UUID id);
    @Update("UPDATE reading_spend_ledger SET expires_at=least(expires_at,CURRENT_TIMESTAMP),stop_reason=#{reason},revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND stop_reason IS NULL") @Options(timeout=3)
    int stopSpend(@Param("id")UUID id,@Param("reason")String reason);
    default void freezeSpend(UUID id) { stopSpend(id,"BATCH_STOPPED"); }
    @Select("SELECT count(*) FROM reading_ai_call WHERE (task_id=#{id} OR index_job_id=#{id}) AND status IN ('STARTED','UNKNOWN')") @Options(timeout=3)
    int unfinished(UUID id);
    @Select("SELECT count(*) FROM reading_ai_call WHERE (task_id=#{id} OR index_job_id=#{id}) AND phase=#{phase}") @Options(timeout=3)
    int phaseCalls(@Param("id")UUID id,@Param("phase")String phase);
    @Update("UPDATE reading_ai_call SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE (task_id=#{id} OR index_job_id=#{id}) AND status='STARTED'") @Options(timeout=3)
    void unknown(UUID id);
    @Select("SELECT ordinal,phase,status,usage_json::text AS usage,reserved_amount,settled_amount,input_bytes,input_token_bound,authorization_id FROM reading_ai_call WHERE task_id=#{id} OR index_job_id=#{id} ORDER BY ordinal") @Options(timeout=3)
    List<Map<String,Object>> calls(UUID id);
    @Select("""
        SELECT p.id,p.version_id,p.chapter_id,p.paragraph_ordinal,p.chapter_paragraph_ordinal,p.text,p.code_point_count,p.text_hash
        FROM book_paragraph p JOIN book_version v ON v.id=p.version_id JOIN reading_book b ON b.id=v.book_id
        WHERE v.owner_id=#{owner} AND b.owner_id=#{owner} AND b.id=#{book} AND p.version_id=#{version}
        AND p.paragraph_ordinal>=#{from} AND p.paragraph_ordinal<=#{boundary}
        AND p.deleted=0 AND v.deleted=0 AND b.deleted=0 AND v.status='READY'
        ORDER BY p.paragraph_ordinal LIMIT #{limit}
        """) @Options(timeout=3)
    List<BookRows.Paragraph> paragraphs(@Param("owner")UUID owner,@Param("book")UUID book,@Param("version")UUID version,
                                       @Param("from")int from,@Param("boundary")int boundary,@Param("limit")int limit);
    @Select("""
        SELECT c.first_paragraph_ordinal FROM book_chapter c
        WHERE c.version_id=#{version} AND c.deleted=0 AND c.chapter_ordinal=greatest(1,
        (SELECT chapter_ordinal FROM book_chapter WHERE version_id=#{version} AND deleted=0
          AND first_paragraph_ordinal<=#{boundary} AND last_paragraph_ordinal>=#{boundary})-#{count}+1)
        """) @Options(timeout=3)
    int recapStart(@Param("version")UUID version,@Param("boundary")int boundary,@Param("count")int count);
    @Insert("""
        INSERT INTO reading_task_evidence(id,task_id,version_id,evidence_key,source_json,text_hash)
        VALUES(#{id},#{task},#{version},#{key},CAST(#{source} AS jsonb),#{hash}) ON CONFLICT(task_id,evidence_key) DO NOTHING
        """) @Options(timeout=3)
    void evidence(@Param("id")UUID id,@Param("task")UUID task,@Param("version")UUID version,@Param("key")String key,@Param("source")String source,@Param("hash")String hash);
    @Select("SELECT source_json::text FROM reading_task_evidence WHERE task_id=#{task} AND evidence_key=#{key} AND deleted=0") @Options(timeout=3)
    String evidenceJson(@Param("task")UUID task,@Param("key")String key);
    @Select("""
        SELECT coalesce(min(p.paragraph_ordinal)-1,#{boundary}) FROM book_paragraph p
        WHERE p.version_id=#{version} AND p.deleted=0 AND p.paragraph_ordinal<=#{boundary}
        AND EXISTS (SELECT 1 FROM generate_series(0,p.code_point_count-1,1000) AS part(start_offset)
          WHERE NOT EXISTS (SELECT 1 FROM reading_index_segment s
             JOIN reading_index_job j ON j.id=s.generation JOIN reading_vector_store x ON x.id=s.id
             WHERE s.paragraph_id=p.id AND s.version_id=p.version_id AND s.text_hash=p.text_hash
             AND s.profile_id=#{profile} AND s.status='READY' AND s.deleted=0
             AND j.deleted=0 AND s.start_offset=part.start_offset AND s.end_offset=least(part.start_offset+1000,p.code_point_count)))
        """) @Options(timeout=3)
    int readyBoundary(@Param("version")UUID version,@Param("boundary")int boundary,@Param("profile")String profile);
    @Select("SELECT * FROM reading_index_job WHERE version_id=#{version} AND owner_id=#{owner} AND deleted=0 AND status IN ('QUEUED','RUNNING','PAUSED') LIMIT 1") @Options(timeout=3)
    ReadingWork activeIndexWork(@Param("owner")UUID owner,@Param("version")UUID version);
    @Insert("""
        INSERT INTO reading_index_segment(id,version_id,paragraph_id,paragraph_ordinal,start_offset,end_offset,text_hash,profile_id,generation,status)
        VALUES(#{id},#{version},#{s.paragraphId},#{s.paragraphOrdinal},#{s.startOffset},#{s.endOffset},#{s.textHash},#{profile},#{job},'READY')
        """) @Options(timeout=3)
    void segment(@Param("id")UUID id,@Param("version")UUID version,@Param("s")ReadingWork.Span span,@Param("profile")String profile,@Param("job")UUID job);
    @Insert("INSERT INTO reading_vector_store(id,content,embedding) VALUES(#{id},#{text},CAST(#{vector} AS vector))") @Options(timeout=3)
    void vector(@Param("id")UUID id,@Param("text")String text,@Param("vector")String vector);
    @Select("""
        WITH candidates AS MATERIALIZED (
          SELECT p.id AS paragraph_id,p.chapter_id,p.paragraph_ordinal,s.start_offset,s.end_offset,s.text_hash,x.content AS text,x.embedding
          FROM reading_index_segment s JOIN reading_vector_store x ON x.id=s.id
          JOIN reading_index_job j ON j.id=s.generation
          JOIN book_paragraph p ON p.id=s.paragraph_id AND p.version_id=s.version_id
          JOIN book_version v ON v.id=p.version_id JOIN reading_book b ON b.id=v.book_id
          WHERE v.owner_id=#{owner} AND b.owner_id=#{owner} AND b.id=#{book} AND v.id=#{version}
            AND p.paragraph_ordinal<=#{boundary} AND s.profile_id=#{profile} AND s.text_hash=p.text_hash
            AND s.status='READY'
            AND s.deleted=0 AND j.deleted=0 AND p.deleted=0 AND v.deleted=0 AND b.deleted=0 AND v.status='READY'
        )
        SELECT paragraph_id,chapter_id,paragraph_ordinal,start_offset,end_offset,text_hash,text FROM candidates
        ORDER BY embedding <=> CAST(#{vector} AS vector),paragraph_ordinal,start_offset LIMIT 8
        """) @Options(timeout=3)
    List<ReadingWork.Span> search(@Param("owner")UUID owner,@Param("book")UUID book,@Param("version")UUID version,
                                 @Param("boundary")int boundary,@Param("profile")String profile,@Param("vector")String vector);
}
