package io.github.fanqiepi.contextpilot.book;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import io.github.fanqiepi.contextpilot.document.StorageService;
import io.github.fanqiepi.contextpilot.readingstate.*;
import io.github.fanqiepi.contextpilot.readingnote.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none",
        "contextpilot.storage.root=target/reading-test-uploads","contextpilot.task-workspace.enabled=false","contextpilot.upgrade-research.enabled=false"})
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class ReadingLibraryPersistenceTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired BookImportService imports;
    @Autowired BookParser parser;
    @Autowired BookService books;
    @Autowired BookMapper mapper;
    @Autowired ReadingStateService states;
    @Autowired ReadingNoteService notes;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext web;
    @Autowired StorageService storage;
    @Autowired ReadingScopeService scopes;

    record Fixture(BookImportService.Registered registered,BookImportService.Confirmation result,List<BookRows.Paragraph> paragraphs) {}
    private BookImportService.Registered preview(String text,UUID target) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        var registration=imports.register(new BookImportService.Request(UUID.randomUUID(),"测试小说","自创测试语料","TXT_CN_CHAPTER_V1",null,target),"story.txt",bytes);
        storage.store(registration.row().storageKey,new java.io.ByteArrayInputStream(bytes));
        imports.parsed(registration.row().ownerId,registration.row().id,parser.parse(bytes,BookParser.Rule.TXT_CN_CHAPTER_V1,null));
        return registration;
    }
    private Fixture fixture() {
        var registration=preview("第一章 开始\n故事"+UUID.randomUUID()+"\n甲😀乙\n第二章 揭密\n隐藏身份",null);
        var result=imports.confirm(registration.row().id,imports.preview(registration.row().id).previewHash());
        var chapter=books.chapters(result.versionId(),1,50).items().getFirst();
        return new Fixture(registration,result,books.paragraphs(result.versionId(),chapter.id(),null,100,false).paragraphs());
    }
    @Test void confirmationIsConcurrentIdempotentAndDeletionDoesNotRemoveOriginal() throws Exception {
        var registration=preview("第一章 开始\n"+UUID.randomUUID()+"\n正文",null);
        String hash=imports.preview(registration.row().id).previewHash();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var results=pool.invokeAll(Collections.<Callable<BookImportService.Confirmation>>nCopies(4,()->imports.confirm(registration.row().id,hash)));
            assertThat(results.stream().map(f->{try{return f.get().versionId();}catch(Exception e){throw new RuntimeException(e);}}).distinct()).hasSize(1);
        }
        var result=imports.confirm(registration.row().id,hash);
        var book=books.get(result.bookId());books.delete(book.id(),book.revision());
        assertThatThrownBy(()->states.get(result.versionId())).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->imports.confirm(registration.row().id,hash)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(410));
        assertThat(storage.exists(registration.row().storageKey)).isTrue();
    }
    @Test void progressCommandsAreIndependentIdempotentAndRejectCrossVersionLocations() {
        var f=fixture();var p=f.paragraphs().getLast();UUID version=f.result().versionId();
        var initial=states.get(version);
        var request=new ReadingStateService.Resume(UUID.randomUUID(),initial.resumeRevision(),p.id(),2);
        var saved=states.resume(version,request);assertThat(saved.state().readBoundary()).isZero();
        assertThat(states.resume(version,request).state().resumeRevision()).isEqualTo(1);
        assertThat(states.get(version).resumePosition().offset()).isEqualTo(2);
        assertThatThrownBy(()->states.resume(version,new ReadingStateService.Resume(UUID.randomUUID(),0L,p.id(),0)))
                .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("REVISION_CONFLICT"));
        var read=states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,p.id(),true));
        assertThat(read.state().resumeRevision()).isEqualTo(1);
        assertThat(read.state().readBoundary()).isEqualTo(2);
        var back=states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),1L,null,true));
        assertThat(back.state().visibilityEpoch()).isEqualTo(1);
        var other=fixture();
        assertThatThrownBy(()->states.resume(version,new ReadingStateService.Resume(UUID.randomUUID(),1L,other.paragraphs().getFirst().id(),0))).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->states.resume(version,new ReadingStateService.Resume(UUID.randomUUID(),1L,p.id(),4))).isInstanceOf(ReadingException.class);
    }
    @Test void chapterTitlesAndNotesAreHiddenAfterRollbackAndQuotedTextIsVerified() {
        var f=fixture();UUID version=f.result().versionId();var p=f.paragraphs().getLast();
        assertThat(books.chapters(version,1,50).items().getFirst().title()).isNull();
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,p.id(),true));
        var note=notes.create(version,new ReadingNoteService.Create(UUID.randomUUID(),"EXCERPT","摘录","😀",p.id(),1,2,1L));
        assertThat(note.body()).isEqualTo("😀");
        var edited=notes.edit(note.id(),new ReadingNoteService.Edit(0L,1L,"摘录想法","我对这段的理解"));
        assertThat(edited.userEdited()).isTrue();
        assertThat(edited.originalExcerpt()).isEqualTo("😀");
        assertThatThrownBy(()->notes.create(version,new ReadingNoteService.Create(UUID.randomUUID(),"EXCERPT","伪造","错误",p.id(),1,2,1L))).isInstanceOf(ReadingException.class);
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),1L,null,true));
        assertThat(notes.get(note.id()).body()).isNull();assertThat(notes.get(note.id()).title()).isNull();
        assertThat(notes.list(version,1,20).items().getFirst().visibility()).isEqualTo("HIDDEN_BY_SCOPE");
        assertThat(books.chapters(version,1,50).items().getFirst().title()).isNull();
    }
    @Test void importAndNoteFailuresRollBackAndVersionsRemainIndependent() {
        var f=fixture();
        var next=preview("第一章 新版\n"+UUID.randomUUID()+"\n改写",f.result().bookId());
        // Force a failure after the version INSERT. No partial version may survive.
        jdbc.execute("CREATE FUNCTION reading_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER reading_test_failure BEFORE INSERT ON book_paragraph FOR EACH ROW EXECUTE FUNCTION reading_test_fail()");
        try {
            assertThatThrownBy(()->imports.confirm(next.row().id,imports.preview(next.row().id).previewHash())).isInstanceOf(RuntimeException.class);
            assertThat(books.versions(f.result().bookId())).hasSize(1);
            assertThat(imports.preview(next.row().id).status()).isEqualTo("PREVIEW_READY");
        } finally { jdbc.execute("DROP TRIGGER reading_test_failure ON book_paragraph");jdbc.execute("DROP FUNCTION reading_test_fail()"); }
        var result=imports.confirm(next.row().id,imports.preview(next.row().id).previewHash());
        assertThat(states.get(result.versionId()).resumePosition()).isNull();
        var request=new ReadingNoteService.Create(UUID.randomUUID(),"MANUAL","感想","自己的记录",null,null,null,0L);
        var note=notes.create(result.versionId(),request);notes.delete(note.id(),0);
        assertThatThrownBy(()->notes.create(result.versionId(),request)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(410));
    }
    @Test void httpRejectsUnknownFieldsAndReturnsNoStoreAndScopedParagraphs() throws Exception {
        var f=fixture();var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        mvc.perform(get("/api/reading/versions/"+f.result().versionId()+"/state")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(put("/api/reading/versions/"+f.result().versionId()+"/resume-position").contentType("application/json")
                .content("{\"ownerId\":\"forged\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reading/books?page=0")).andExpect(status().isBadRequest());
        var p=f.paragraphs().getLast();
        mvc.perform(get("/api/reading/versions/"+f.result().versionId()+"/chapters/"+p.chapterId()+"/paragraphs").param("atParagraphId",p.id().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paragraphs[0].id").value(p.id().toString())).andExpect(jsonPath("$.title").isEmpty());
    }
    @Test void multipartUploadParsesAsynchronouslyAndCanBeConfirmedWithoutModels() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        var file=new org.springframework.mock.web.MockMultipartFile("file","demo.txt","text/plain",
                ("第一章 开始\n"+UUID.randomUUID()+"\n正文").getBytes(StandardCharsets.UTF_8));
        String response=mvc.perform(multipart("/api/reading/imports").file(file).param("clientRequestId",UUID.randomUUID().toString())
                .param("title","异步导入").param("parserId","TXT_CN_CHAPTER_V1"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id=ReadingText.read(response,BookImportService.Preview.class).id();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(imports.preview(id).status().equals("PARSING") && System.nanoTime()<deadline) Thread.sleep(25);
        var preview=imports.preview(id);
        assertThat(preview.status()).isEqualTo("PREVIEW_READY");
        mvc.perform(post("/api/reading/imports/"+id+"/confirm").contentType("application/json").content(ReadingText.json(new ReadingController.Confirm(preview.previewHash()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versionId").isNotEmpty());
    }
    @Test void fiveMillionCharactersRemainPagedAndPreserveTheLastLocation() {
        var text=new StringBuilder(5_020_000);
        for(int chapter=1;chapter<=1000;chapter++) {
            text.append("第").append(chapter).append("章 测试\n");
            for(int paragraph=0;paragraph<10;paragraph++) text.append("甲".repeat(500)).append('\n');
        }
        long started=System.nanoTime();
        var registration=preview(text.toString(),null);
        long parsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        started=System.nanoTime();
        var result=imports.confirm(registration.row().id,imports.preview(registration.row().id).previewHash());
        long confirmedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        var version=mapper.version(registration.row().ownerId,result.versionId());
        assertThat(version.characterCount()).isEqualTo(5_000_000);
        var lastChapter=books.chapters(result.versionId(),20,50).items().getLast();
        var rows=books.paragraphs(result.versionId(),lastChapter.id(),null,100,false);
        assertThat(rows.paragraphs().stream().mapToInt(BookRows.Paragraph::codePointCount).sum()).isLessThanOrEqualTo(20000);
        var last=rows.paragraphs().getLast();
        states.resume(result.versionId(),new ReadingStateService.Resume(UUID.randomUUID(),0L,last.id(),500));
        assertThat(states.get(result.versionId()).resumePosition().paragraphOrdinal()).isEqualTo(10000);
        long[] timings=new long[30];
        for(int i=0;i<30;i++) {
            long start=System.nanoTime();books.paragraphs(result.versionId(),lastChapter.id(),null,100,false);
            timings[i]=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        }
        Arrays.sort(timings);
        System.out.printf("READING_CAPACITY characters=5000000 chapters=1000 parseMs=%d confirmMs=%d paragraphP95Ms=%d%n",parsedMs,confirmedMs,timings[28]);
        assertThat(parsedMs).isLessThan(120000);assertThat(confirmedMs).isLessThan(120000);
        assertThat(timings[28]).isLessThanOrEqualTo(500);
    }
    @Test void duplicateFilesReuseVersionAndReparseInvalidatesPriorPreview() {
        String text="第一章 开始\n"+UUID.randomUUID()+"\n第二章 结尾\n正文";
        var first=preview(text,null);
        var created=imports.confirm(first.row().id,imports.preview(first.row().id).previewHash());
        var second=preview(text,null);
        var duplicate=imports.confirm(second.row().id,imports.preview(second.row().id).previewHash());
        assertThat(duplicate.duplicateVersionId()).isEqualTo(created.versionId());
        var pending=preview("第一章 待改\n"+UUID.randomUUID(),null);
        UUID key=UUID.randomUUID();
        var replacement=imports.reparse(pending.row().id,key,"SINGLE_CHAPTER_V1",null);
        assertThat(imports.preview(pending.row().id).status()).isEqualTo("EXPIRED");
        assertThat(imports.reparse(pending.row().id,key,"SINGLE_CHAPTER_V1",null).row().id).isEqualTo(replacement.row().id);
        assertThatThrownBy(()->imports.confirm(pending.row().id,imports.preview(pending.row().id).previewHash())).isInstanceOf(ReadingException.class);
        imports.fail(replacement.row().ownerId,replacement.row().id,"TEST_INTERRUPTED");
    }
    @Test void otherOwnerResourcesAreNotEnumerableAndBookmarkDeletionDoesNotRecycleRequestKeys() {
        UUID owner=UUID.randomUUID(),book=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,user_key,display_name,status) VALUES(?,?,?,'ACTIVE')",owner,"TEST_"+owner,"Other test owner");
        jdbc.update("INSERT INTO reading_book(id,owner_id,title) VALUES(?,?,?)",book,owner,"Other owner's book");
        assertThatThrownBy(()->books.get(book)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThat(books.list(1,50).items()).noneMatch(b->b.id().equals(book));
        var f=fixture();var p=f.paragraphs().getFirst();
        var request=new ReadingNoteService.BookmarkCreate(UUID.randomUUID(),p.id(),0,"位置");
        var saved=notes.bookmark(f.result().versionId(),request);
        assertThatThrownBy(()->notes.bookmark(f.result().versionId(),new ReadingNoteService.BookmarkCreate(UUID.randomUUID(),p.id(),0,"重复位置")))
                .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("BOOKMARK_EXISTS"));
        notes.deleteBookmark(saved.id(),0);
        assertThatThrownBy(()->notes.bookmark(f.result().versionId(),request)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(410));
        assertThat(notes.bookmark(f.result().versionId(),new ReadingNoteService.BookmarkCreate(UUID.randomUUID(),p.id(),0,"重新保留")).id()).isNotEqualTo(saved.id());
    }

    @Test void scopedSourcesExcludeSameChapterFutureAndNeverMoveResume() {
        var f=fixture();UUID version=f.result().versionId();var first=f.paragraphs().getFirst();var later=f.paragraphs().getLast();
        assertThatThrownBy(()->scopes.preview(version,first.id())).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("SCOPE_HIDDEN"));
        states.resume(version,new ReadingStateService.Resume(UUID.randomUUID(),0L,later.id(),2));
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,first.id(),true));
        var source=scopes.preview(version,first.id());
        assertThat(source.text()).isEqualTo(first.text());
        assertThat(source.chapterTitle()).isNull();
        assertThat(source.requiredBoundary()).isEqualTo(1);
        assertThat(mapper.scopedParagraph(states.owner(),f.result().bookId(),version,later.id(),1)).isNull();
        assertThatThrownBy(()->scopes.preview(version,later.id())).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("SCOPE_HIDDEN"));
        var state=states.get(version);
        assertThat(state.resumeRevision()).isEqualTo(1);
        assertThat(state.resumePosition().paragraphId()).isEqualTo(later.id());
        assertThat(state.resumePosition().offset()).isEqualTo(2);
        assertThat(state.readBoundary()).isEqualTo(1);
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),1L,later.id(),true));
        var titled=scopes.preview(version,first.id());
        assertThat(titled.chapterTitle()).isEqualTo("第一章 开始");
        assertThat(titled.requiredBoundary()).isEqualTo(2); // A visible title depends on the complete chapter.
    }

    @Test void scopeRejectsOtherBooksVersionsOwnersAndDeletedSources() {
        var f=fixture();var other=fixture();UUID version=f.result().versionId();
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,f.paragraphs().getLast().id(),true));
        assertThatThrownBy(()->scopes.preview(version,other.paragraphs().getFirst().id())).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(404));
        var next=preview("第一章 新版\n不同结局"+UUID.randomUUID(),f.result().bookId());
        var nextVersion=imports.confirm(next.row().id,imports.preview(next.row().id).previewHash()).versionId();
        assertThatThrownBy(()->scopes.preview(nextVersion,f.paragraphs().getFirst().id())).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(404));
        var frozen=scopes.capture(version);
        var forged=new ReadingScopeService.Snapshot(UUID.randomUUID(),frozen.bookId(),version,1,frozen.scopeRevision(),frozen.visibilityEpoch(),frozen.dataAsOf());
        assertThatThrownBy(()->scopes.publish(forged,s->"must not publish")).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThat(mapper.scopedParagraph(UUID.randomUUID(),frozen.bookId(),version,f.paragraphs().getFirst().id(),2)).isNull();
        var book=books.get(f.result().bookId());books.delete(book.id(),book.revision());
        assertThatThrownBy(()->scopes.capture(version)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->scopes.publish(frozen,s->"must not publish")).isInstanceOf(ReadingException.class);
    }

    @Test void noteSourceUsesEntireDependencyAndUnicodeOffsetsAfterRollback() {
        var f=fixture();UUID version=f.result().versionId();var p=f.paragraphs().getLast();
        var lastChapter=books.chapters(version,1,50).items().getLast();
        var finalParagraph=books.paragraphs(version,lastChapter.id(),null,100,false).paragraphs().getLast();
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,finalParagraph.id(),true));
        var note=notes.create(version,new ReadingNoteService.Create(UUID.randomUUID(),"EXCERPT","后期理解","😀",p.id(),1,2,1L));
        assertThat(notes.source(note.id()).text()).isEqualTo("😀");
        assertThat(notes.source(note.id()).requiredBoundary()).isEqualTo(3);
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),1L,p.id(),true));
        assertThat(scopes.preview(version,p.id()).text()).isEqualTo(p.text());
        assertThatThrownBy(()->notes.source(note.id())).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("SCOPE_HIDDEN"));
        var hidden=notes.get(note.id());
        assertThat(hidden.title()).isNull();assertThat(hidden.body()).isNull();assertThat(hidden.originalExcerpt()).isNull();assertThat(hidden.anchorParagraphId()).isNull();
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),2L,finalParagraph.id(),true));
        assertThat(notes.source(note.id()).text()).isEqualTo("😀");
        notes.delete(note.id(),0);
        assertThatThrownBy(()->notes.source(note.id())).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.status()).isEqualTo(404));
    }

    @Test void frozenScopeDoesNotExpandAndRollbackThenAdvanceNeverRevivesPublication() {
        var f=fixture();UUID version=f.result().versionId();
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,f.paragraphs().getFirst().id(),true));
        var frozen=scopes.capture(version);
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),1L,f.paragraphs().getLast().id(),true));
        assertThat(scopes.publish(frozen,ReadingScopeService.Snapshot::boundaryOrdinal)).isEqualTo(1);
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),2L,null,true));
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),3L,f.paragraphs().getLast().id(),true));
        assertThatThrownBy(()->scopes.publish(frozen,s->"late result")).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("SCOPE_CHANGED"));
    }

    @Test void publicationAndRollbackSerializeAndFailedPublicationRollsBack() throws Exception {
        var f=fixture();UUID version=f.result().versionId();
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,f.paragraphs().getLast().id(),true));
        var frozen=scopes.capture(version);
        var note=notes.create(version,new ReadingNoteService.Create(UUID.randomUUID(),"MANUAL","记录","原内容",null,null,null,1L));
        assertThatThrownBy(()->scopes.publish(frozen,s -> {
            jdbc.update("UPDATE reading_note SET body=? WHERE id=?","不可提交",note.id());
            throw new IllegalStateException("injected");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notes.get(note.id()).body()).isEqualTo("原内容");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var rollbackStarted=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var publication=pool.submit(()->scopes.publish(frozen,s -> {
                entered.countDown();
                try { if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("test deadline"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
                jdbc.update("UPDATE reading_note SET body=? WHERE id=?","已提交内容",note.id());
                return true;
            }));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var rollback=pool.submit(()-> { rollbackStarted.countDown();return states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),1L,null,true)); });
            assertThat(rollbackStarted.await(2,TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(()->rollback.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(publication.get(3,TimeUnit.SECONDS)).isTrue();
            assertThat(rollback.get(3,TimeUnit.SECONDS).state().readBoundary()).isZero();
        } finally { release.countDown(); }
        assertThat(notes.get(note.id()).body()).isNull();
        assertThatThrownBy(()->scopes.publish(frozen,s->true)).isInstanceOf(ReadingException.class);
    }

    @Test void sourceHttpRejectsRangeOverridesAndStateStreamReplaysOnlyCurrentMetadata() throws Exception {
        var f=fixture();UUID version=f.result().versionId();var p=f.paragraphs().getFirst();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),0L,p.id(),true));
        String endpoint="/api/reading/versions/"+version;
        mvc.perform(get(endpoint+"/paragraphs/"+p.id()+"/preview")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.text").value(p.text())).andExpect(jsonPath("$.chapterTitle").isEmpty());
        mvc.perform(get(endpoint+"/paragraphs/"+p.id()+"/preview").param("revealTitle","true")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reading/versions/"+UUID.randomUUID()+"/state/events").accept("text/event-stream"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/json"));
        states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),1L,null,true));
        var stream=mvc.perform(get(endpoint+"/state/events").header("Last-Event-ID",version+"/1"))
                .andExpect(request().asyncStarted()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        try {
            awaitStream(stream,"event:snapshot");
            String initial=stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(initial).contains("\"readBoundary\":0","\"scopeRevision\":2","\"visibilityEpoch\":1").doesNotContain(p.text(),"textHash","ownerId");
            states.boundary(version,new ReadingStateService.Boundary(UUID.randomUUID(),2L,p.id(),true));
            awaitStream(stream,"event:scope_changed");
            assertThat(stream.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"scopeRevision\":3").doesNotContain(p.text());
        } finally {
            var context=(org.springframework.mock.web.MockAsyncContext)stream.getRequest().getAsyncContext();
            for(var listener:context.getListeners()) listener.onTimeout(new jakarta.servlet.AsyncEvent(context));
        }
    }
    private static void awaitStream(org.springframework.test.web.servlet.MvcResult result,String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        String content;
        do {
            content=result.getResponse().getContentAsString(StandardCharsets.UTF_8).replace("\r\n","\n");
            int eventStart=content.indexOf(expected);
            if(eventStart>=0 && content.indexOf("\n\n",eventStart)>=0)return;
            Thread.sleep(25);
        } while(System.nanoTime()<deadline);
        fail("No complete SSE frame for %s; received: %s",expected,content);
    }

    @Test void stateStreamsHaveBoundedSubscriptionsAndReleaseDisconnectedSlots() throws Exception {
        var f=fixture();var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        String path="/api/reading/versions/"+f.result().versionId()+"/state/events";
        var streams=new ArrayList<org.springframework.test.web.servlet.MvcResult>();
        try {
            for(int i=0;i<16;i++) streams.add(mvc.perform(get(path).accept("text/event-stream")).andExpect(request().asyncStarted()).andReturn());
            mvc.perform(get(path).accept("text/event-stream")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("STREAM_LIMIT"));
            var context=(org.springframework.mock.web.MockAsyncContext)streams.removeFirst().getRequest().getAsyncContext();
            for(var listener:context.getListeners())listener.onTimeout(new jakarta.servlet.AsyncEvent(context));
            streams.add(mvc.perform(get(path).accept("text/event-stream")).andExpect(request().asyncStarted()).andReturn());
        } finally {
            for(var stream:streams) {
                var context=(org.springframework.mock.web.MockAsyncContext)stream.getRequest().getAsyncContext();
                for(var listener:context.getListeners())listener.onTimeout(new jakarta.servlet.AsyncEvent(context));
            }
        }
    }
}
