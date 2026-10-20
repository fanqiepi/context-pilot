package io.github.fanqiepi.contextpilot.readingstate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import io.github.fanqiepi.contextpilot.book.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading-only boundary gate. Callbacks must be short database operations, never model/network calls.
 * The lifecycle lock precedes the state lock, exactly as in boundary updates and book removal.
 */
@Service
public class ReadingScopeService {
    public record Snapshot(UUID ownerId,UUID bookId,UUID versionId,int boundaryOrdinal,long scopeRevision,
                           long visibilityEpoch,OffsetDateTime dataAsOf) {}
    public record Source(UUID versionId,UUID chapterId,int chapterOrdinal,String chapterTitle,
                         UUID paragraphId,int paragraphOrdinal,int startOffset,int endOffset,String text,
                         String textHash,int requiredBoundary,long scopeRevision,long visibilityEpoch,OffsetDateTime dataAsOf) {}
    private final ReadingStateService states;
    private final BookMapper books;

    public ReadingScopeService(ReadingStateService states,BookMapper books) { this.states=states;this.books=books; }

    @Transactional(timeout=5)
    public Snapshot capture(UUID versionId) { return locked(versionId,Function.identity()); }

    /** Shared transaction for visibility decisions and materializing their response. Never take a client snapshot. */
    @Transactional(timeout=5)
    public <T> T current(UUID versionId,Function<Snapshot,T> read) { return locked(versionId,read); }

    private <T> T locked(UUID versionId,Function<Snapshot,T> read) {
        UUID owner=states.owner();
        books.serialize(owner);
        var version=states.version(owner,versionId);
        var state=states.lock(owner,version);
        return read.apply(new Snapshot(owner,version.bookId(),versionId,state.boundaryOrdinal,state.scopeRevision,
                state.visibilityEpoch,OffsetDateTime.now()));
    }

    /**
     * A frozen input scope can publish only in its original epoch. Advancing B never expands its input.
     * Future task services must additionally check executionId and task state inside this callback.
     */
    @Transactional(timeout=5)
    public <T> T publish(Snapshot frozen,Function<Snapshot,T> persist) {
        return locked(frozen.versionId(),current -> {
            sameResource(frozen,current);
            if(frozen.visibilityEpoch()!=current.visibilityEpoch() || frozen.scopeRevision()>current.scopeRevision()
                    || frozen.boundaryOrdinal()>current.boundaryOrdinal())
                throw ReadingException.conflict("SCOPE_CHANGED","已读范围曾回退，请重新发起操作");
            return persist.apply(frozen);
        });
    }

    public static boolean visible(Snapshot scope,UUID versionId,int requiredBoundary) {
        return scope.versionId().equals(versionId) && requiredBoundary>=0 && requiredBoundary<=scope.boundaryOrdinal();
    }
    public static void requireVisible(Snapshot scope,UUID versionId,int requiredBoundary) {
        if(!visible(scope,versionId,requiredBoundary))
            throw ReadingException.conflict("SCOPE_HIDDEN","内容超出当前已读范围");
    }
    private static void sameResource(Snapshot frozen,Snapshot current) {
        if(!frozen.ownerId().equals(current.ownerId()) || !frozen.bookId().equals(current.bookId())
                || !frozen.versionId().equals(current.versionId())) throw ReadingException.missing();
    }

    @Transactional(timeout=5)
    public Source preview(UUID versionId,UUID paragraphId) {
        return locked(versionId,scope -> source(scope,paragraphId,null,null,0));
    }

    /** Called under current() by source-owning services; dependency includes the entire original input. */
    public Source source(Snapshot scope,UUID paragraphId,Integer start,Integer end,int requiredBoundary) {
        requireVisible(scope,scope.versionId(),requiredBoundary);
        // The SQL itself excludes unread candidates, including later paragraphs in an already opened chapter.
        var p=books.scopedParagraph(scope.ownerId(),scope.bookId(),scope.versionId(),paragraphId,scope.boundaryOrdinal());
        if(p==null) {
            // Distinguish a known but unread paragraph without returning any of its content.
            if(books.paragraphOrdinal(scope.versionId(),paragraphId)==null) throw ReadingException.missing();
            throw ReadingException.conflict("SCOPE_HIDDEN","原文超出当前已读范围");
        }
        int from=start==null?0:start, to=end==null?p.codePointCount():end;
        ReadingText.require(from>=0 && to>from && to<=p.codePointCount(),"原文选区不合法");
        var c=books.chapter(scope.versionId(),p.chapterId());
        if(c==null) throw ReadingException.missing();
        boolean titleVisible=c.lastParagraphOrdinal()<=scope.boundaryOrdinal();
        int outputBoundary=Math.max(requiredBoundary,titleVisible?c.lastParagraphOrdinal():p.paragraphOrdinal());
        return new Source(scope.versionId(),c.id(),c.chapterOrdinal(),
                titleVisible?c.title():null,p.id(),p.paragraphOrdinal(),
                from,to,ReadingText.slice(p.text(),from,to),p.textHash(),outputBoundary,
                scope.scopeRevision(),scope.visibilityEpoch(),scope.dataAsOf());
    }
}
