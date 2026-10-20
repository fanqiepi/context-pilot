package io.github.fanqiepi.contextpilot.readingnote;

import java.time.OffsetDateTime;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReadingNoteService {
    public record Create(UUID clientRequestId,String kind,String title,String body,UUID anchorParagraphId,
                         Integer anchorStart,Integer anchorEnd,Long expectedScopeRevision) {}
    public record Edit(Long expectedRevision,Long expectedScopeRevision,String title,String body) {}
    public record BookmarkCreate(UUID clientRequestId,UUID paragraphId,Integer offset,String label) {}
    public record NoteView(UUID id,UUID versionId,String visibility,String kind,String title,String body,UUID anchorParagraphId,
                           UUID chapterId,Integer anchorStart,Integer anchorEnd,long revision,OffsetDateTime createdAt,
                           boolean userEdited,String originalExcerpt) {}
    public record BookmarkView(UUID id,UUID paragraphId,UUID chapterId,int offset,String label,String visibility,long revision) {}
    private final ReadingStateService states;
    private final BookMapper books;
    private final ReadingNoteMapper notes;
    private final ReadingBookmarkMapper bookmarks;
    public ReadingNoteService(ReadingStateService states,BookMapper books,ReadingNoteMapper notes,ReadingBookmarkMapper bookmarks) {
        this.states=states;this.books=books;this.notes=notes;this.bookmarks=bookmarks;
    }
    public BookService.Page<NoteView> list(UUID version,int page,int size) {
        BookService.page(page,size,50);UUID owner=states.owner();states.version(owner,version);
        int boundary=states.boundary(owner,version);
        return new BookService.Page<>(notes.list(owner,version,size,(long)(page-1)*size).stream().map(n->view(n,boundary)).toList(),page,size,notes.count(owner,version));
    }
    public NoteView get(UUID id) {
        var note=requireNote(id,false);return view(note,states.boundary(note.ownerId,note.versionId));
    }
    @Transactional(timeout=5)
    public NoteView create(UUID versionId,Create request) {
        ReadingText.require(request.clientRequestId()!=null && request.expectedScopeRevision()!=null
                && Set.of("MANUAL","EXCERPT").contains(Objects.toString(request.kind(),"")),"笔记请求不完整");
        UUID owner=states.owner();books.serialize(owner);
        var version=states.version(owner,versionId);var state=states.lock(owner,version);
        String hash=ReadingText.hash(ReadingText.json(Arrays.asList(versionId,request)));
        var previous=notes.byRequest(owner,request.clientRequestId());
        if(previous!=null) {
            replay(previous.requestHash,hash,previous.deleted);
            return view(previous,state.boundaryOrdinal);
        }
        ReadingStateService.revision(state.scopeRevision,request.expectedScopeRevision());
        var note=new ReadingNote();note.id=UUID.randomUUID();note.ownerId=owner;note.bookId=version.bookId();note.versionId=versionId;
        note.clientRequestId=request.clientRequestId();note.requestHash=hash;note.kind=request.kind();
        note.title=ReadingText.bounded(request.title(),0,120,"标题");note.body=ReadingText.bounded(request.body(),1,5000,"笔记");
        note.requiredBoundary=state.boundaryOrdinal;
        if(request.anchorParagraphId()!=null) {
            var paragraph=states.paragraph(versionId,request.anchorParagraphId());
            ReadingText.require(request.anchorStart()!=null && request.anchorEnd()!=null && request.anchorStart()>=0
                    && request.anchorEnd()>=request.anchorStart() && request.anchorEnd()<=paragraph.codePointCount(),"笔记选区不合法");
            note.anchorParagraphId=paragraph.id();note.anchorStart=request.anchorStart();note.anchorEnd=request.anchorEnd();
            note.requiredBoundary=Math.max(note.requiredBoundary,paragraph.paragraphOrdinal());
            if("EXCERPT".equals(note.kind) && !ReadingText.slice(paragraph.text(),note.anchorStart,note.anchorEnd).strip().equals(note.body))
                throw ReadingException.invalid("EXCERPT_MISMATCH","摘录与选区原文不一致");
        } else ReadingText.require("MANUAL".equals(note.kind) && request.anchorStart()==null && request.anchorEnd()==null,"摘录必须有有效选区");
        notes.create(note);
        return view(note,state.boundaryOrdinal);
    }
    @Transactional(timeout=5)
    public NoteView edit(UUID id,Edit request) {
        ReadingText.require(request.expectedRevision()!=null && request.expectedScopeRevision()!=null,"缺少修订号");
        UUID owner=states.owner();books.serialize(owner);
        var note=requireNote(id,false);var version=states.version(owner,note.versionId);var state=states.lock(owner,version);
        ReadingStateService.revision(note.revision,request.expectedRevision());
        ReadingStateService.revision(state.scopeRevision,request.expectedScopeRevision());
        if(note.requiredBoundary>state.boundaryOrdinal) throw ReadingException.conflict("SCOPE_HIDDEN","笔记超出当前已读范围");
        note.title=ReadingText.bounded(request.title(),0,120,"标题");
        String body=ReadingText.bounded(request.body(),1,5000,"笔记");
        note.body=body;note.requiredBoundary=Math.max(note.requiredBoundary,state.boundaryOrdinal);note.revision++;
        note.updatedAt=OffsetDateTime.now();notes.save(note);
        return view(note,state.boundaryOrdinal);
    }
    @Transactional(timeout=5)
    public void delete(UUID id,long revision) {
        books.serialize(states.owner());var note=requireNote(id,true);
        if(note.deleted!=0)return;
        ReadingStateService.revision(note.revision,revision);notes.remove(id);
    }
    public BookService.Page<BookmarkView> bookmarks(UUID version,int page,int size) {
        BookService.page(page,size,50);UUID owner=states.owner();states.version(owner,version);int boundary=states.boundary(owner,version);
        return new BookService.Page<>(bookmarks.list(owner,version,size,(long)(page-1)*size).stream().map(b->bookmarkView(b,boundary)).toList(),
                page,size,bookmarks.count(owner,version));
    }
    @Transactional(timeout=5)
    public BookmarkView bookmark(UUID versionId,BookmarkCreate request) {
        ReadingText.require(request.clientRequestId()!=null && request.paragraphId()!=null && request.offset()!=null,"书签请求不完整");
        UUID owner=states.owner();books.serialize(owner);var version=states.version(owner,versionId);
        var state=states.lock(owner,version);String hash=ReadingText.hash(ReadingText.json(Arrays.asList(versionId,request)));
        var previous=bookmarks.byRequest(owner,request.clientRequestId());
        if(previous!=null) { replay(previous.requestHash,hash,previous.deleted);return bookmarkView(previous,state.boundaryOrdinal); }
        var paragraph=states.paragraph(versionId,request.paragraphId());
        ReadingText.require(request.offset()>=0 && request.offset()<=paragraph.codePointCount(),"书签位置不合法");
        var existing=bookmarks.at(owner,versionId,paragraph.id(),request.offset());
        if(existing!=null) {
            throw ReadingException.conflict("BOOKMARK_EXISTS","这个位置已保留书签");
        }
        var bookmark=new ReadingBookmark();bookmark.id=UUID.randomUUID();bookmark.ownerId=owner;bookmark.bookId=version.bookId();
        bookmark.versionId=versionId;bookmark.clientRequestId=request.clientRequestId();bookmark.requestHash=hash;bookmark.paragraphId=paragraph.id();
        bookmark.positionOffset=request.offset();bookmark.label=ReadingText.bounded(request.label(),0,120,"标签");
        bookmark.requiredBoundary=Math.max(state.boundaryOrdinal,paragraph.paragraphOrdinal());bookmarks.create(bookmark);
        return bookmarkView(bookmark,state.boundaryOrdinal);
    }
    @Transactional(timeout=5)
    public void deleteBookmark(UUID id,long revision) {
        UUID owner=states.owner();books.serialize(owner);var bookmark=bookmarks.owned(owner,id);
        if(bookmark==null)throw ReadingException.missing();states.version(owner,bookmark.versionId);
        if(bookmark.deleted!=0)return;
        ReadingStateService.revision(bookmark.revision,revision);bookmarks.remove(id);
    }
    private ReadingNote requireNote(UUID id,boolean includeDeleted) {
        UUID owner=states.owner();var note=notes.owned(owner,id);
        if(note==null || (!includeDeleted && note.deleted!=0))throw ReadingException.missing();
        states.version(owner,note.versionId);return note;
    }
    private NoteView view(ReadingNote note,int boundary) {
        if(note.requiredBoundary>boundary) return new NoteView(note.id,note.versionId,"HIDDEN_BY_SCOPE",null,null,null,null,null,null,null,note.revision,null,false,null);
        var anchor=note.anchorParagraphId==null?null:states.paragraph(note.versionId,note.anchorParagraphId);
        UUID chapter=anchor==null?null:anchor.chapterId();
        String original=anchor!=null && "EXCERPT".equals(note.kind)?ReadingText.slice(anchor.text(),note.anchorStart,note.anchorEnd).strip():null;
        return new NoteView(note.id,note.versionId,"VISIBLE",note.kind,note.title,note.body,note.anchorParagraphId,chapter,
                note.anchorStart,note.anchorEnd,note.revision,note.createdAt,original!=null && !original.equals(note.body),original);
    }
    private BookmarkView bookmarkView(ReadingBookmark bookmark,int boundary) {
        return new BookmarkView(bookmark.id,bookmark.paragraphId,states.paragraph(bookmark.versionId,bookmark.paragraphId).chapterId(),
                bookmark.positionOffset,bookmark.requiredBoundary>boundary?null:bookmark.label,
                bookmark.requiredBoundary>boundary?"HIDDEN_BY_SCOPE":"VISIBLE",bookmark.revision);
    }
    private void replay(String previous,String hash,int deleted) {
        if(!previous.equals(hash))throw ReadingException.conflict("IDEMPOTENCY_CONFLICT","请求标识已用于其他内容");
        if(deleted!=0)throw new ReadingException(410,"RESOURCE_REMOVED","资源已移除");
    }
}
