package io.github.fanqiepi.contextpilot.book;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookImportService {
    public record Request(UUID clientRequestId, String title, String sourceDescription, String parserId,
                          Integer headingLevel, UUID targetBookId) {}
    public record Registered(BookImportEntity row, boolean created) {}
    public record Preview(UUID id, String status, String errorCode, String title, String parserId, Integer headingLevel,
                          String previewHash, OffsetDateTime expiresAt, UUID resultVersionId, UUID duplicateVersionId,
                          int chapterCount, int paragraphCount, int characterCount, int splitCount, String firstSample, String lastSample) {}
    public record Confirmation(UUID bookId, UUID versionId, UUID duplicateVersionId) {}
    private final BookMapper mapper;
    private final BookParser parser;
    private final CurrentSubjectProvider subjects;

    public BookImportService(BookMapper mapper, BookParser parser, CurrentSubjectProvider subjects) {
        this.mapper = mapper; this.parser = parser; this.subjects = subjects;
    }

    @Transactional(timeout=5)
    public Registered register(Request request, String filename, byte[] bytes) {
        UUID owner = subjects.currentSubject().id();
        ReadingText.require(request.clientRequestId() != null, "缺少请求标识");
        parser.rule(request.parserId(), request.headingLevel());
        filename = ReadingText.bounded(filename, 1, 255, "文件名");
        String extension = filename.toLowerCase(Locale.ROOT);
        if (!extension.endsWith(".txt") && !extension.endsWith(".md")) throw ReadingException.invalid("UNSUPPORTED_FORMAT", "只支持 TXT/Markdown");
        if (bytes.length == 0) throw ReadingException.invalid("EMPTY_TEXT", "文件没有正文");
        if (bytes.length > 20*1024*1024) throw new ReadingException(413, "IMPORT_LIMIT_EXCEEDED", "文件超过 20 MiB");
        String title = ReadingText.bounded(request.title(), 1, 120, "书名");
        String source = ReadingText.bounded(request.sourceDescription(), 0, 1000, "来源说明");
        String fileHash = ReadingText.hash(bytes);
        String hash = ReadingText.hash(ReadingText.json(Arrays.asList(request, filename, fileHash)));
        mapper.serialize(owner);
        var old = mapper.byRequest(owner, request.clientRequestId());
        if (old != null) { sameRequest(old, hash); return new Registered(old, false); }
        if (request.targetBookId() != null) requireBook(owner, request.targetBookId());
        if (mapper.previews(owner) >= 3) throw ReadingException.conflict("PREVIEW_LIMIT", "最多保留三个活动预览，请确认或等待过期");
        if (mapper.reservedBytes(owner) + bytes.length > 1024L*1024*1024) throw ReadingException.conflict("STORAGE_LIMIT", "已保留原文件达到 1 GiB 上限，需要独立维护");
        var row = new BookImportEntity();
        row.id = UUID.randomUUID(); row.ownerId = owner; row.clientRequestId = request.clientRequestId();
        row.requestHash = hash; row.originalFilename = filename; row.fileHash = fileHash; row.fileBytes = bytes.length;
        row.ownsFile = true; row.storageKey = "reading/" + owner + "/" + row.id + "/source";
        row.title = title; row.sourceDescription = source; row.parserId = request.parserId(); row.headingLevel = request.headingLevel();
        row.status = "PARSING"; row.targetBookId = request.targetBookId(); row.expiresAt = OffsetDateTime.now().plusHours(24);
        mapper.createImport(row);
        return new Registered(row, true);
    }

    @Transactional(timeout=5)
    public Registered reparse(UUID id, UUID key, String rule, Integer level) {
        UUID owner = subjects.currentSubject().id();
        ReadingText.require(key != null, "缺少请求标识");
        parser.rule(rule, level);
        mapper.serialize(owner);
        var previous = requireImport(owner, id);
        String hash = ReadingText.hash(ReadingText.json(Arrays.asList(id, rule, level)));
        var replay = mapper.byRequest(owner, key);
        if (replay != null) { sameRequest(replay, hash); return new Registered(replay, false); }
        if ("CONFIRMED".equals(previous.status)) throw ReadingException.conflict("IMPORT_ALREADY_CONFIRMED", "已确认版本请通过新导入更换规则");
        if (previous.targetBookId != null) requireBook(owner, previous.targetBookId);
        boolean previousActive = previous.expiresAt.isAfter(OffsetDateTime.now()) && Set.of("PARSING","PREVIEW_READY").contains(previous.status);
        if (mapper.previews(owner) - (previousActive ? 1 : 0) >= 3) throw ReadingException.conflict("PREVIEW_LIMIT", "活动预览数量达到上限");
        previous.status = "EXPIRED"; previous.errorCode = "SUPERSEDED"; mapper.saveImport(previous);
        var row = new BookImportEntity();
        row.id=UUID.randomUUID(); row.ownerId=owner; row.clientRequestId=key; row.requestHash=hash;
        row.originalFilename=previous.originalFilename; row.fileHash=previous.fileHash; row.fileBytes=previous.fileBytes;
        row.storageKey=previous.storageKey; row.ownsFile=false; row.title=previous.title; row.sourceDescription=previous.sourceDescription;
        row.parserId=rule; row.headingLevel=level; row.targetBookId=previous.targetBookId;
        row.status="PARSING"; row.expiresAt=OffsetDateTime.now().plusHours(24);
        mapper.createImport(row);
        return new Registered(row,true);
    }

    @Transactional(timeout=5)
    public void parsed(UUID owner, UUID id, BookParser.Parsed parsed) {
        mapper.serialize(owner);
        var row = requireImport(owner,id);
        if (!"PARSING".equals(row.status)) return;
        row.previewJson = ReadingText.json(parsed);
        row.previewHash = ReadingText.hash(ReadingText.json(Arrays.asList(row.fileHash,row.parserId,row.headingLevel,row.targetBookId,parsed.textHash())));
        row.status = row.expiresAt.isAfter(OffsetDateTime.now()) ? "PREVIEW_READY" : "EXPIRED";
        mapper.saveImport(row);
    }

    @Transactional(timeout=5)
    public void fail(UUID owner, UUID id, String code) {
        mapper.serialize(owner);
        var row = requireImport(owner,id);
        if (!"PARSING".equals(row.status)) return;
        row.status="FAILED"; row.errorCode=code; mapper.saveImport(row);
    }

    public Preview preview(UUID id) {
        var row = requireImport(subjects.currentSubject().id(), id);
        boolean expired = row.expiresAt.isBefore(OffsetDateTime.now()) && !"CONFIRMED".equals(row.status);
        var parsed = row.previewJson == null || expired ? null : ReadingText.read(row.previewJson, BookParser.Parsed.class);
        var duplicate = mapper.duplicate(row.ownerId,row.fileHash,row.parserId,options(row.headingLevel));
        // Do not expose a removed duplicate's metadata.
        UUID duplicateId = duplicate == null || mapper.version(row.ownerId,duplicate.id()) == null ? null : duplicate.id();
        String first = "", last = "";
        if (parsed != null) {
            first = parsed.chapters().getFirst().paragraphs().getFirst();
            last = parsed.chapters().getLast().paragraphs().getLast();
            first = ReadingText.slice(first,0,Math.min(500,ReadingText.length(first)));
            last = ReadingText.slice(last,Math.max(0,ReadingText.length(last)-500),ReadingText.length(last));
        }
        return new Preview(row.id,expired?"EXPIRED":row.status,row.errorCode,row.title,row.parserId,row.headingLevel,row.previewHash,row.expiresAt,
                row.resultVersionId,duplicateId,parsed==null?0:parsed.chapters().size(),parsed==null?0:parsed.paragraphCount(),
                parsed==null?0:parsed.characterCount(),parsed==null?0:parsed.splitCount(),first,last);
    }

    public List<Map<String,Object>> chapters(UUID id, int page, int size) {
        BookService.page(page,size,100);
        var row = requireImport(subjects.currentSubject().id(),id);
        ready(row);
        var parsed = ReadingText.read(row.previewJson,BookParser.Parsed.class);
        int start = (int)Math.min((long)(page-1)*size,parsed.chapters().size());
        var result = new ArrayList<Map<String,Object>>();
        for(int index=start;index<Math.min(start+size,parsed.chapters().size());index++)
            result.add(Map.of("ordinal",index+1,"title",parsed.chapters().get(index).title(),"paragraphCount",parsed.chapters().get(index).paragraphs().size()));
        return result;
    }

    @Transactional(timeout=120)
    public Confirmation confirm(UUID id, String previewHash) {
        UUID owner=subjects.currentSubject().id();
        mapper.serialize(owner);
        var row=requireImport(owner,id);
        if (!Objects.equals(row.previewHash,previewHash) || previewHash==null) throw ReadingException.conflict("PREVIEW_CHANGED","目录预览已变化，请重新核对");
        if ("CONFIRMED".equals(row.status)) {
            var version=mapper.version(owner,row.resultVersionId);
            if(version==null) throw new ReadingException(410,"RESOURCE_REMOVED","原版本已移除");
            return new Confirmation(version.bookId(),version.id(),null);
        }
        ready(row);
        if(row.targetBookId!=null) requireBook(owner,row.targetBookId);
        var duplicate=mapper.duplicate(owner,row.fileHash,row.parserId,options(row.headingLevel));
        if(duplicate!=null) {
            if(mapper.version(owner,duplicate.id())==null) throw ReadingException.conflict("DUPLICATE_VERSION_REMOVED","相同版本已移除，不能通过重新导入复活");
            row.resultVersionId=duplicate.id(); row.status="CONFIRMED"; mapper.saveImport(row);
            return new Confirmation(duplicate.bookId(),duplicate.id(),duplicate.id());
        }
        UUID bookId=row.targetBookId;
        if(bookId==null) {
            if(mapper.bookCount(owner)>=100) throw ReadingException.conflict("BOOK_LIMIT","书架已达到 100 本");
            bookId=UUID.randomUUID();
            mapper.createBook(new BookRows.Book(bookId,owner,row.title,row.sourceDescription,null,0,null,0,null,null));
        } else if(mapper.versionCount(bookId)>=5) throw ReadingException.conflict("VERSION_LIMIT","每书最多保留五个版本");
        var parsed=ReadingText.read(row.previewJson,BookParser.Parsed.class);
        UUID versionId=UUID.randomUUID();
        var version=new BookRows.Version(versionId,owner,bookId,row.fileHash,parsed.textHash(),row.parserId,1,options(row.headingLevel),
                parsed.chapters().size(),parsed.paragraphCount(),parsed.characterCount(),null,0);
        mapper.createVersion(version,id);
        var chapters=new ArrayList<BookRows.Chapter>();
        var paragraphs=new ArrayList<BookRows.Paragraph>();
        int ordinal=1,chapterOrdinal=1;
        for(var chapter:parsed.chapters()) {
            UUID chapterId=stable(versionId,"chapter:"+chapterOrdinal);
            chapters.add(new BookRows.Chapter(chapterId,versionId,chapterOrdinal++,chapter.title(),ordinal,
                    ordinal+chapter.paragraphs().size()-1,ReadingText.hash(ReadingText.json(chapter.paragraphs()))));
            int local=1;
            for(String text:chapter.paragraphs()) paragraphs.add(new BookRows.Paragraph(stable(versionId,"paragraph:"+ordinal),versionId,
                    chapterId,ordinal++,local++,text,ReadingText.length(text),ReadingText.hash(text)));
        }
        for(int i=0;i<chapters.size();i+=500) mapper.chapters(chapters.subList(i,Math.min(i+500,chapters.size())));
        for(int i=0;i<paragraphs.size();i+=250) mapper.paragraphs(paragraphs.subList(i,Math.min(i+250,paragraphs.size())));
        mapper.latest(bookId,versionId);
        row.resultVersionId=versionId; row.status="CONFIRMED"; mapper.saveImport(row);
        return new Confirmation(bookId,versionId,null);
    }

    public BookImportEntity requireImport(UUID owner,UUID id) {
        var row=mapper.imported(owner,id);
        if(row==null) throw ReadingException.missing();
        return row;
    }
    private void ready(BookImportEntity row) {
        if(row.expiresAt.isBefore(OffsetDateTime.now()) || "EXPIRED".equals(row.status)) throw new ReadingException(410,"PREVIEW_EXPIRED","预览已过期，请重新解析");
        if(!"PREVIEW_READY".equals(row.status)) throw ReadingException.conflict("PREVIEW_NOT_READY","请等待解析完成或更换规则");
    }
    private void sameRequest(BookImportEntity row,String hash) {
        if(!row.requestHash.equals(hash)) throw ReadingException.conflict("IDEMPOTENCY_CONFLICT","请求标识已用于不同内容");
        if(row.deleted!=0) throw new ReadingException(410,"RESOURCE_REMOVED","导入记录已移除");
    }
    private void requireBook(UUID owner,UUID id) {
        var book=mapper.book(owner,id);
        if(book==null || book.deleted()!=0) throw ReadingException.missing();
    }
    private String options(Integer level) { return ReadingText.hash(ReadingText.json(level)); }
    private UUID stable(UUID version,String key) { return UUID.nameUUIDFromBytes((version+":"+key).getBytes(StandardCharsets.UTF_8)); }
}
