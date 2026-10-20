package io.github.fanqiepi.contextpilot.book;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import io.github.fanqiepi.contextpilot.document.StorageService;
import io.github.fanqiepi.contextpilot.readingstate.ReadingStateService;
import io.github.fanqiepi.contextpilot.readingstate.ReadingScopeService;
import io.github.fanqiepi.contextpilot.readingstate.ReadingScopeEvents;
import io.github.fanqiepi.contextpilot.readingnote.ReadingNoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/reading")
public class ReadingController {
    private final BookImportService imports;
    private final BookImportWorker worker;
    private final BookService books;
    private final ReadingStateService states;
    private final ReadingNoteService notes;
    private final StorageService storage;
    private final ReadingScopeService scopes;
    private final ReadingScopeEvents scopeEvents;
    public ReadingController(BookImportService imports,BookImportWorker worker,BookService books,ReadingStateService states,
                             ReadingNoteService notes,StorageService storage,ReadingScopeService scopes,ReadingScopeEvents scopeEvents) {
        this.imports=imports;this.worker=worker;this.books=books;this.states=states;this.notes=notes;this.storage=storage;
        this.scopes=scopes;this.scopeEvents=scopeEvents;
    }
    @PostMapping("/imports")
    public ResponseEntity<?> upload(@RequestParam MultipartFile file,@RequestParam UUID clientRequestId,@RequestParam String title,
                                     @RequestParam(defaultValue="") String sourceDescription,@RequestParam String parserId,
                                     @RequestParam(required=false) Integer headingLevel,@RequestParam(required=false) UUID targetBookId,
                                     jakarta.servlet.http.HttpServletRequest servletRequest) throws java.io.IOException {
        var allowed=java.util.Set.of("clientRequestId","title","sourceDescription","parserId","headingLevel","targetBookId");
        if(servletRequest.getParameterMap().keySet().stream().anyMatch(k->!allowed.contains(k))) throw ReadingException.invalid("INVALID_REQUEST","不支持的导入字段");
        if(file.getSize()>20*1024*1024)throw new ReadingException(413,"IMPORT_LIMIT_EXCEEDED","文件超过 20 MiB");
        byte[] bytes;
        try(var input=file.getInputStream()) { bytes=input.readNBytes(20*1024*1024+1); }
        var registration=imports.register(new BookImportService.Request(clientRequestId,title,sourceDescription,parserId,headingLevel,targetBookId),
                file.getOriginalFilename(),bytes);
        if(registration.created()) {
            try { storage.store(registration.row().storageKey,new ByteArrayInputStream(bytes)); }
            catch(RuntimeException error) { imports.fail(registration.row().ownerId,registration.row().id,"STORAGE_FAILED");throw new ReadingException(503,"STORAGE_FAILED","原文件保存失败，可重新导入"); }
            worker.submit(registration.row());
        }
        return ResponseEntity.status(registration.created()?202:200).body(imports.preview(registration.row().id));
    }
    public record Reparse(UUID clientRequestId,String parserId,Integer headingLevel) {}
    public record Confirm(String previewHash) {}
    @PostMapping("/imports/{id}/reparse")
    public ResponseEntity<?> reparse(@PathVariable UUID id,@RequestBody String body) {
        var request=ReadingText.read(body,Reparse.class);
        var registration=imports.reparse(id,request.clientRequestId(),request.parserId(),request.headingLevel());
        if(registration.created())worker.submit(registration.row());
        return ResponseEntity.status(registration.created()?202:200).body(imports.preview(registration.row().id));
    }
    @GetMapping("/imports/{id}") public Object imported(@PathVariable UUID id) { return imports.preview(id); }
    @GetMapping("/imports/{id}/chapters") public Object previewChapters(@PathVariable UUID id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="50")int size) { return imports.chapters(id,page,size); }
    @PostMapping("/imports/{id}/confirm") public Object confirm(@PathVariable UUID id,@RequestBody String body) { return imports.confirm(id,ReadingText.read(body,Confirm.class).previewHash()); }
    @GetMapping("/books") public Object books(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size) { return books.list(page,size); }
    @GetMapping("/books/{id}") public Object book(@PathVariable UUID id) { return books.get(id); }
    @GetMapping("/books/{id}/versions") public Object versions(@PathVariable UUID id) { return books.versions(id); }
    @PatchMapping("/books/{id}") public Object editBook(@PathVariable UUID id,@RequestBody String body) { return books.edit(id,ReadingText.read(body,BookService.Edit.class)); }
    @DeleteMapping("/books/{id}") public ResponseEntity<Void> deleteBook(@PathVariable UUID id,@RequestParam long expectedRevision) { books.delete(id,expectedRevision);return ResponseEntity.noContent().build(); }
    @GetMapping("/versions/{id}/chapters") public Object chapters(@PathVariable UUID id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="50")int size) { return books.chapters(id,page,size); }
    @GetMapping("/versions/{id}/chapters/{chapter}/paragraphs")
    public Object paragraphs(@PathVariable UUID id,@PathVariable UUID chapter,@RequestParam(required=false)String cursor,
                             @RequestParam(required=false)UUID atParagraphId,
                             @RequestParam(defaultValue="100")int limit,@RequestParam(defaultValue="false")boolean revealTitle) {
        if(atParagraphId!=null) {
            ReadingText.require(cursor==null,"不能同时指定游标与定位段落");
            states.version(states.owner(),id);
            var paragraph=states.paragraph(id,atParagraphId);
            if(!paragraph.chapterId().equals(chapter))throw ReadingException.missing();
            cursor=BookService.cursor(id,chapter,paragraph.paragraphOrdinal()-1);
        }
        return books.paragraphs(id,chapter,cursor,limit,revealTitle);
    }
    @GetMapping("/versions/{id}/state") public Object state(@PathVariable UUID id) { return states.get(id); }
    @GetMapping(value="/versions/{id}/state/events",produces="text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stateEvents(@PathVariable UUID id,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Accel-Buffering","no");
        return scopeEvents.subscribe(id);
    }
    @GetMapping("/versions/{id}/paragraphs/{paragraph}/preview")
    public Object preview(@PathVariable UUID id,@PathVariable UUID paragraph,jakarta.servlet.http.HttpServletRequest request) {
        ReadingText.require(request.getParameterMap().isEmpty(),"原文预览不接受扩展范围或显示标题参数");
        return scopes.preview(id,paragraph);
    }
    @GetMapping("/notes/{id}/source") public Object noteSource(@PathVariable UUID id) { return notes.source(id); }
    @PutMapping("/versions/{id}/resume-position") public Object resume(@PathVariable UUID id,@RequestBody String body) { return states.resume(id,ReadingText.read(body,ReadingStateService.Resume.class)); }
    @PutMapping("/versions/{id}/read-boundary") public Object boundary(@PathVariable UUID id,@RequestBody String body) { return states.boundary(id,ReadingText.read(body,ReadingStateService.Boundary.class)); }
    @GetMapping("/versions/{id}/notes") public Object notes(@PathVariable UUID id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size) { return notes.list(id,page,size); }
    @GetMapping("/notes/{id}") public Object note(@PathVariable UUID id) { return notes.get(id); }
    @PostMapping("/versions/{id}/notes") public ResponseEntity<?> createNote(@PathVariable UUID id,@RequestBody String body) { return ResponseEntity.status(201).body(notes.create(id,ReadingText.read(body,ReadingNoteService.Create.class))); }
    @PatchMapping("/notes/{id}") public Object editNote(@PathVariable UUID id,@RequestBody String body) { return notes.edit(id,ReadingText.read(body,ReadingNoteService.Edit.class)); }
    @DeleteMapping("/notes/{id}") public ResponseEntity<Void> deleteNote(@PathVariable UUID id,@RequestParam long expectedRevision) { notes.delete(id,expectedRevision);return ResponseEntity.noContent().build(); }
    @GetMapping("/versions/{id}/bookmarks") public Object bookmarks(@PathVariable UUID id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size) { return notes.bookmarks(id,page,size); }
    @PostMapping("/versions/{id}/bookmarks") public ResponseEntity<?> bookmark(@PathVariable UUID id,@RequestBody String body) { return ResponseEntity.status(201).body(notes.bookmark(id,ReadingText.read(body,ReadingNoteService.BookmarkCreate.class))); }
    @DeleteMapping("/bookmarks/{id}") public ResponseEntity<Void> deleteBookmark(@PathVariable UUID id,@RequestParam long expectedRevision) { notes.deleteBookmark(id,expectedRevision);return ResponseEntity.noContent().build(); }
}
