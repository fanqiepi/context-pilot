package io.github.fanqiepi.contextpilot.book;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import io.github.fanqiepi.contextpilot.readingstate.ReadingStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookService {
    public record Page<T>(List<T> items,int page,int size,long total) {}
    public record Chapter(UUID id,int chapterOrdinal,String title,boolean titleHidden,int firstParagraphOrdinal,int lastParagraphOrdinal) {}
    public record Paragraphs(UUID versionId,UUID chapterId,String title,List<BookRows.Paragraph> paragraphs,String nextCursor,OffsetDateTime dataAsOf) {}
    public record Edit(Long expectedRevision,String title,String sourceDescription) {}
    private final BookMapper mapper;
    private final ReadingStateService states;
    public BookService(BookMapper mapper,ReadingStateService states) { this.mapper=mapper;this.states=states; }
    public Page<BookRows.Book> list(int page,int size) {
        page(page,size,50); UUID owner=states.owner();
        return new Page<>(mapper.books(owner,size,(long)(page-1)*size),page,size,mapper.bookCount(owner));
    }
    public BookRows.Book get(UUID id) {
        var book=mapper.book(states.owner(),id);
        if(book==null || book.deleted()!=0) throw ReadingException.missing();
        return book;
    }
    public List<BookRows.Version> versions(UUID id) { get(id); return mapper.versions(id); }
    @Transactional(timeout=5)
    public BookRows.Book edit(UUID id,Edit request) {
        ReadingText.require(request.expectedRevision()!=null,"缺少修订号");
        mapper.serialize(states.owner());
        var book=get(id);ReadingStateService.revision(book.revision(),request.expectedRevision());
        mapper.editBook(id,ReadingText.bounded(request.title(),1,120,"书名"),ReadingText.bounded(request.sourceDescription(),0,1000,"来源说明"));
        return get(id);
    }
    @Transactional(timeout=5)
    public void delete(UUID id,long revision) {
        UUID owner=states.owner();mapper.serialize(owner);
        var book=mapper.book(owner,id);
        if(book==null) throw ReadingException.missing();
        if(book.deleted()!=0) return;
        ReadingStateService.revision(book.revision(),revision);
        mapper.removeBook(id);
    }
    public Page<Chapter> chapters(UUID versionId,int page,int size) {
        page(page,size,100);UUID owner=states.owner();
        var version=states.version(owner,versionId);int boundary=states.boundary(owner,versionId);
        var items=mapper.chapterPage(versionId,size,(long)(page-1)*size).stream()
                .map(c -> new Chapter(c.id(),c.chapterOrdinal(),c.lastParagraphOrdinal()<=boundary?c.title():null,
                        c.lastParagraphOrdinal()>boundary,c.firstParagraphOrdinal(),c.lastParagraphOrdinal())).toList();
        return new Page<>(items,page,size,version.chapterCount());
    }
    public Paragraphs paragraphs(UUID version,UUID chapter,String cursor,int limit,boolean revealTitle) {
        ReadingText.require(limit>=1 && limit<=100,"正文每页最多 100 段");
        UUID owner=states.owner();states.version(owner,version);
        var c=mapper.chapter(version,chapter);if(c==null) throw ReadingException.missing();
        int after=c.firstParagraphOrdinal()-1;
        if(cursor!=null && !cursor.isBlank()) {
            try {
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":");
                if(parts.length!=3 || !UUID.fromString(parts[0]).equals(version) || !UUID.fromString(parts[1]).equals(chapter)) throw new IllegalArgumentException();
                after=Integer.parseInt(parts[2]);
                if(after<c.firstParagraphOrdinal()-1 || after>c.lastParagraphOrdinal()) throw new IllegalArgumentException();
            } catch(RuntimeException invalid) { throw ReadingException.invalid("INVALID_CURSOR","正文游标不属于当前章节"); }
        }
        var rows=mapper.paragraphPage(version,chapter,after,limit);
        var items=new ArrayList<BookRows.Paragraph>();int characters=0;
        for(var p:rows) {
            if(characters+p.codePointCount()>20000) break;
            items.add(p);characters+=p.codePointCount();
        }
        int last=items.isEmpty()?after:items.getLast().paragraphOrdinal();
        String next=last<c.lastParagraphOrdinal()?cursor(version,chapter,last):null;
        int boundary=states.boundary(owner,version);
        return new Paragraphs(version,chapter,revealTitle || c.lastParagraphOrdinal()<=boundary?c.title():null,List.copyOf(items),next,OffsetDateTime.now());
    }
    public static String cursor(UUID version,UUID chapter,int after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((version+":"+chapter+":"+after).getBytes(StandardCharsets.UTF_8));
    }
    public static void page(int page,int size,int maximum) { ReadingText.require(page>=1 && size>=1 && size<=maximum,"分页参数超出范围"); }
}
