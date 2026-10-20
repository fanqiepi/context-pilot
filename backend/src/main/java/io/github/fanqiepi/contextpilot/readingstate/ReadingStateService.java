package io.github.fanqiepi.contextpilot.readingstate;

import java.time.OffsetDateTime;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReadingStateService {
    public record Position(UUID paragraphId,UUID chapterId,int paragraphOrdinal,int offset) {}
    public record View(UUID versionId,Position resumePosition,long resumeRevision,int readBoundary,long scopeRevision,
                       long visibilityEpoch,OffsetDateTime dataAsOf) {}
    public record Resume(UUID clientRequestId,Long expectedResumeRevision,UUID paragraphId,Integer offset) {}
    public record Boundary(UUID clientRequestId,Long expectedScopeRevision,UUID lastReadParagraphId,Boolean confirmContiguousPrefix) {}
    public record Applied(long resultRevision,View state) {}
    private final BookMapper books;
    private final ReadingStateMapper mapper;
    private final CurrentSubjectProvider subjects;
    public ReadingStateService(BookMapper books,ReadingStateMapper mapper,CurrentSubjectProvider subjects) {
        this.books=books;this.mapper=mapper;this.subjects=subjects;
    }
    public UUID owner() { return subjects.currentSubject().id(); }
    public BookRows.Version version(UUID owner,UUID id) {
        var version=books.version(owner,id);
        if(version==null) throw ReadingException.missing();
        return version;
    }
    @Transactional(timeout=5)
    public View get(UUID id) {
        UUID owner=owner();
        var version=version(owner,id);
        mapper.initialize(UUID.randomUUID(),owner,version.bookId(),id);
        return view(mapper.state(owner,id));
    }
    public int boundary(UUID owner,UUID version) {
        var state=mapper.state(owner,version);
        return state==null?0:state.boundaryOrdinal;
    }
    public ReadingState lock(UUID owner,BookRows.Version version) {
        mapper.initialize(UUID.randomUUID(),owner,version.bookId(),version.id());
        return mapper.lock(owner,version.id());
    }
    public BookRows.Paragraph paragraph(UUID version,UUID id) {
        var paragraph=books.paragraph(version,id);
        if(paragraph==null) throw ReadingException.missing();
        return paragraph;
    }
    @Transactional(timeout=5)
    public Applied resume(UUID id,Resume request) {
        ReadingText.require(request.clientRequestId()!=null && request.expectedResumeRevision()!=null && request.expectedResumeRevision()>=0
                && request.paragraphId()!=null && request.offset()!=null,"位置请求字段不完整");
        UUID owner=owner(); books.serialize(owner);
        var version=version(owner,id);
        var state=lock(owner,version);
        String hash=ReadingText.hash(ReadingText.json(request));
        var replay=replay(owner,id,request.clientRequestId(),"RESUME",hash);
        if(replay!=null) return new Applied(replay.resultRevision(),view(state));
        revision(state.resumeRevision,request.expectedResumeRevision());
        var paragraph=paragraph(id,request.paragraphId());
        ReadingText.require(request.offset()>=0 && request.offset()<=paragraph.codePointCount(),"段内位置超出范围");
        state.resumeParagraphId=paragraph.id(); state.resumeOffset=request.offset();
        state.resumeRevision++; state.lastReadAt=OffsetDateTime.now();mapper.save(state);
        mapper.commandResult(UUID.randomUUID(),owner,id,request.clientRequestId(),"RESUME",hash,state.resumeRevision);
        return new Applied(state.resumeRevision,view(state));
    }
    @Transactional(timeout=5)
    public Applied boundary(UUID id,Boundary request) {
        ReadingText.require(request.clientRequestId()!=null && request.expectedScopeRevision()!=null && request.expectedScopeRevision()>=0
                && Boolean.TRUE.equals(request.confirmContiguousPrefix()),"必须明确确认连续已读范围");
        UUID owner=owner();books.serialize(owner);
        var version=version(owner,id);
        var state=lock(owner,version);
        String hash=ReadingText.hash(ReadingText.json(request));
        var replay=replay(owner,id,request.clientRequestId(),"BOUNDARY",hash);
        if(replay!=null) return new Applied(replay.resultRevision(),view(state));
        revision(state.scopeRevision,request.expectedScopeRevision());
        int next=request.lastReadParagraphId()==null?0:paragraph(id,request.lastReadParagraphId()).paragraphOrdinal();
        if(next<state.boundaryOrdinal) state.visibilityEpoch++;
        state.boundaryOrdinal=next;state.scopeRevision++;mapper.save(state);
        mapper.commandResult(UUID.randomUUID(),owner,id,request.clientRequestId(),"BOUNDARY",hash,state.scopeRevision);
        return new Applied(state.scopeRevision,view(state));
    }
    private ReadingStateMapper.Command replay(UUID owner,UUID version,UUID key,String type,String hash) {
        var previous=mapper.command(owner,key);
        if(previous!=null && (!previous.versionId().equals(version) || !previous.commandType().equals(type) || !previous.requestHash().equals(hash)))
            throw ReadingException.conflict("IDEMPOTENCY_CONFLICT","请求标识已用于其他命令");
        return previous;
    }
    public static void revision(long current,long expected) {
        if(current!=expected) throw new ReadingException(409,"REVISION_CONFLICT","另一个页面已更新，请刷新后决定",current);
    }
    private View view(ReadingState state) {
        Position position=null;
        if(state.resumeParagraphId!=null) {
            var p=paragraph(state.versionId,state.resumeParagraphId);
            position=new Position(p.id(),p.chapterId(),p.paragraphOrdinal(),state.resumeOffset);
        }
        return new View(state.versionId,position,state.resumeRevision,state.boundaryOrdinal,state.scopeRevision,state.visibilityEpoch,OffsetDateTime.now());
    }
}
