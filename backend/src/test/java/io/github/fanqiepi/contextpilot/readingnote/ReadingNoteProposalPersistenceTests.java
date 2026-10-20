package io.github.fanqiepi.contextpilot.readingnote;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.*;
import io.github.fanqiepi.contextpilot.readingtask.*;
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
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none",
        "contextpilot.storage.root=target/reading-note-test-uploads","contextpilot.task-workspace.enabled=false","contextpilot.upgrade-research.enabled=false"})
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class ReadingNoteProposalPersistenceTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ReadingNoteProposalService proposals;
    @Autowired ReadingNoteProposalMapper proposalMapper;
    @Autowired ReadingNoteService notes;
    @Autowired ReadingNoteMapper noteMapper;
    @Autowired ReadingTaskMapper tasks;
    @Autowired ReadingTaskService taskService;
    @Autowired BookImportService imports;
    @Autowired BookParser parser;
    @Autowired BookMapper books;
    @Autowired ReadingStateService states;
    @Autowired ReadingScopeService scopes;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext web;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    record Fixture(UUID book,UUID version,UUID task,List<BookRows.Paragraph> paragraphs) {}
    Fixture fixture() {
        byte[] bytes=("第一章 渡口\n林舟来到渡口。\n青禾递给他一封信。\n"+UUID.randomUUID()+"\n第二章 未读的秘密\n此处尚未读到。").getBytes(StandardCharsets.UTF_8);
        var reg=imports.register(new BookImportService.Request(UUID.randomUUID(),"笔记工程故事","自创离线夹具","TXT_CN_CHAPTER_V1",null,null),"note.txt",bytes);
        imports.parsed(reg.row().ownerId,reg.row().id,parser.parse(bytes,BookParser.Rule.TXT_CN_CHAPTER_V1,null));
        var published=imports.confirm(reg.row().id,imports.preview(reg.row().id).previewHash());
        var ps=jdbc.query("SELECT id FROM book_paragraph WHERE version_id=? ORDER BY paragraph_ordinal",
                (rs,n)->books.paragraph(published.versionId(),rs.getObject(1,UUID.class)),published.versionId());
        var f=new Fixture(published.bookId(),published.versionId(),UUID.randomUUID(),ps);boundary(f,2);
        var scope=scopes.capture(f.version);var p=new ReadingWork.Payload();p.scope=scope;
        // Persist a fixed completed result. No provider or embedding is used in this suite.
        var first=ps.getFirst();var span=new ReadingWork.Span(first.id(),first.chapterId(),first.paragraphOrdinal(),0,first.codePointCount(),first.textHash(),first.text());
        var evidence=new ReadingWork.Evidence("E1",List.of(span),first.text());p.evidence=List.of(evidence);
        p.result=new ReadingWork.Result(List.of(new ReadingWork.Fact(first.text(),List.of("E1"))),false);
        var w=new ReadingWork();w.id=f.task;w.ownerId=scope.ownerId();w.bookId=f.book;w.versionId=f.version;w.clientRequestId=UUID.randomUUID();
        w.requestHash=ReadingText.hash("offline fixture");w.type="RECAP";w.status="COMPLETED";w.requiredBoundary=2;
        w.payloadJson=ReadingText.json(p);w.traceId=UUID.randomUUID();tasks.insert(false,w);
        tasks.evidence(UUID.randomUUID(),w.id,w.versionId,evidence.id(),ReadingText.json(evidence),ReadingText.hash(evidence.text()));
        return f;
    }
    void boundary(Fixture f,int ordinal) {
        var state=states.get(f.version);
        states.boundary(f.version,new ReadingStateService.Boundary(UUID.randomUUID(),state.scopeRevision(),ordinal==0?null:f.paragraphs.get(ordinal-1).id(),true));
    }
    ReadingNoteProposalService.Create request(Fixture f) {
        return new ReadingNoteProposalService.Create(UUID.randomUUID(),"前情回顾","林舟来到渡口。",List.of("E1"),states.get(f.version).scopeRevision(),null);
    }
    UUID propose(Fixture f) {return proposals.create(f.task,request(f)).id();}
    ReadingNoteProposal row(UUID id) {return proposalMapper.owned(states.owner(),id);}
    ReadingNoteProposalService.Confirmation confirm(UUID id) {return proposals.confirm(id,new ReadingNoteProposalService.Confirm(row(id).contentHash));}
    long count(Fixture f) {return jdbc.queryForObject("SELECT count(*) FROM reading_note WHERE source_task_id=?",Long.class,f.task);}
    @SuppressWarnings("unchecked") Map<String,Object> view(UUID id) {return (Map<String,Object>)proposals.get(id);}

    @Test void previewIsDurableAndSeparateFromConfirmationAndNotesSurviveSourceTaskRemoval() {
        var f=fixture();var r=request(f);var id=proposals.create(f.task,r).id();
        assertThat(count(f)).isZero();assertThat(row(id).status).isEqualTo("PENDING_CONFIRMATION");
        assertThat(proposals.create(f.task,r).id()).isEqualTo(id);
        assertThat(((Map<?,?>)proposals.latest(f.task)).get("id")).isEqualTo(id);
        assertThat(view(id)).containsEntry("body","林舟来到渡口。").containsEntry("status","PENDING_CONFIRMATION");
        notes.create(f.version,new ReadingNoteService.Create(id,"MANUAL","独立手写","普通内容",null,null,null,states.get(f.version).scopeRevision()));
        assertThat(confirm(id).httpStatus()).isEqualTo(200);assertThat(count(f)).isEqualTo(1);
    }

    @Test void confirmedNoteKeepsFullInputAndIndependentFrozenCitations() {
        var f=fixture();var id=propose(f);assertThat(confirm(id).httpStatus()).isEqualTo(200);
        var n=noteMapper.byTask(states.owner(),f.task);assertThat(n.requiredBoundary).isEqualTo(2);
        assertThat(n.kind).isEqualTo("AI_RECAP");assertThat(n.userEdited).isFalse();
        assertThat(confirm(id).body().get("resultNoteId")).isEqualTo(n.id);assertThat(count(f)).isEqualTo(1);
        assertThat(proposals.create(f.task,request(f)).id()).isEqualTo(id);
        taskService.delete(f.task,0);
        assertThat(ReadingText.json(notes.citation(n.id,"E1"))).contains("林舟来到渡口").doesNotContain("未读的秘密");
        boundary(f,1);
        assertThat(notes.get(n.id).body()).isNull();
        assertThat(view(id)).doesNotContainKeys("title","body","contentHash","retainedEvidenceIds","resultNoteId");
        assertThatThrownBy(()->notes.citation(n.id,"E1")).isInstanceOf(ReadingException.class);
        boundary(f,2);assertThat(notes.get(n.id).body()).isEqualTo("林舟来到渡口。");
        assertThat(states.get(f.version).resumeRevision()).isZero();
    }

    @Test void replacementAndRejectInvalidateOldPreviewWithoutLosingIdempotency() {
        var f=fixture();var first=propose(f);
        assertThatThrownBy(()->proposals.create(f.task,request(f))).isInstanceOf(ReadingException.class).hasMessageContaining("已有待确认");
        var r=new ReadingNoteProposalService.Create(UUID.randomUUID(),"我的回顾","修改后的文字",List.of(),states.get(f.version).scopeRevision(),first);
        var second=proposals.create(f.task,r).id();
        assertThat(row(first).status).isEqualTo("REJECTED");assertThat(row(first).errorCode).isEqualTo("SUPERSEDED");
        assertThat(confirm(first).httpStatus()).isEqualTo(409);assertThat(count(f)).isZero();
        assertThat(proposals.create(f.task,r).id()).isEqualTo(second);
        assertThatThrownBy(()->proposals.create(f.task,new ReadingNoteProposalService.Create(r.clientRequestId(),"不同内容","文字",List.of(),r.expectedScopeRevision(),first)))
                .isInstanceOf(ReadingException.class);
        proposals.reject(second);proposals.reject(second);assertThat(row(second).status).isEqualTo("REJECTED");
        assertThat(confirm(second).httpStatus()).isEqualTo(409);
        var third=propose(f);assertThat(confirm(third).httpStatus()).isEqualTo(200);
        assertThatThrownBy(()->proposals.reject(third)).isInstanceOf(ReadingException.class);
    }

    @Test void concurrentConfirmationAndConcurrentProposalCreationHaveSingleWinner() throws Exception {
        var f=fixture();var id=propose(f);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var gate=new CountDownLatch(1);var work=new ArrayList<Future<ReadingNoteProposalService.Confirmation>>();
            for(int i=0;i<8;i++)work.add(pool.submit(()->{gate.await();return confirm(id);}));
            gate.countDown();
            for(var result:work)assertThat(result.get(12,TimeUnit.SECONDS).httpStatus()).isEqualTo(200);
        }
        assertThat(count(f)).isEqualTo(1);
        var other=fixture();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Boolean> create=()->{gate.await();try{propose(other);return true;}catch(ReadingException e){assertThat(e.code()).isEqualTo("PROPOSAL_PENDING");return false;}};
            var a=pool.submit(create);var b=pool.submit(create);gate.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_note_proposal WHERE task_id=? AND status='PENDING_CONFIRMATION'",Long.class,other.task)).isEqualTo(1);
    }

    @Test void expiryHashMismatchAndHiddenScopePersistTerminalStatesWithoutNotes() {
        var expired=fixture();var exp=propose(expired);
        jdbc.update("UPDATE reading_note_proposal SET expires_at=CURRENT_TIMESTAMP-interval '1 minute' WHERE id=?",exp);
        assertThat(confirm(exp).httpStatus()).isEqualTo(410);assertThat(row(exp).status).isEqualTo("EXPIRED");assertThat(count(expired)).isZero();
        var mismatch=fixture();var bad=propose(mismatch);
        assertThat(proposals.confirm(bad,new ReadingNoteProposalService.Confirm("wrong")).httpStatus()).isEqualTo(409);
        assertThat(row(bad).status).isEqualTo("FAILED");assertThat(count(mismatch)).isZero();
        var rollback=fixture();var old=propose(rollback);boundary(rollback,1);boundary(rollback,2);
        assertThat(confirm(old).httpStatus()).isEqualTo(409);assertThat(row(old).errorCode).isEqualTo("SCOPE_CHANGED");
        assertThat(count(rollback)).isZero();
        var hidden=fixture();var pending=propose(hidden);boundary(hidden,1);
        assertThat(confirm(pending).body()).doesNotContainKeys("body","title","contentHash");
        assertThat(row(pending).status).isEqualTo("FAILED");assertThat(count(hidden)).isZero();
    }

    @Test void databaseFailureAfterInsertRollsBackNoteAndExecutionClaim() {
        var f=fixture();var id=propose(f);
        jdbc.execute("""
            CREATE FUNCTION reading_note_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'injected transaction failure'; END IF; RETURN NEW; END $$;
            """);
        jdbc.execute("CREATE TRIGGER reading_note_test_failure BEFORE UPDATE ON reading_note_proposal FOR EACH ROW EXECUTE FUNCTION reading_note_test_failure()");
        try {
            assertThatThrownBy(()->confirm(id)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("SAVE_RETRYABLE"));
            assertThat(count(f)).isZero();assertThat(row(id).status).isEqualTo("PENDING_CONFIRMATION");assertThat(row(id).resultNoteId).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER reading_note_test_failure ON reading_note_proposal");
            jdbc.execute("DROP FUNCTION reading_note_test_failure()");
        }
        assertThat(confirm(id).httpStatus()).isEqualTo(200);assertThat(count(f)).isEqualTo(1);
    }

    @Test void editingOnlyRemovesCitationsAndRaisesScopeAndDeletionNeverRecreates() {
        var f=fixture();var originalRequest=request(f);var id=proposals.create(f.task,originalRequest).id();confirm(id);var n=noteMapper.byTask(states.owner(),f.task);
        boundary(f,3);
        notes.edit(n.id,new ReadingNoteService.Edit(0L,states.get(f.version).scopeRevision(),"自己的笔记","用户编辑内容",List.of()));
        var edited=notes.get(n.id);assertThat(edited.requiredBoundary()).isEqualTo(3);
        assertThat(edited.userEdited()).isTrue();assertThat(edited.retainedEvidenceIds()).isEmpty();
        assertThat(edited.sourceTaskId()).isEqualTo(f.task);
        assertThatThrownBy(()->notes.edit(n.id,new ReadingNoteService.Edit(1L,states.get(f.version).scopeRevision(),"标题","正文",List.of("E1"))))
                .isInstanceOf(ReadingException.class);
        boundary(f,2);assertThat(notes.get(n.id).body()).isNull();
        boundary(f,3);notes.delete(n.id,1);
        assertThat(confirm(id).httpStatus()).isEqualTo(410);
        assertThatThrownBy(()->proposals.create(f.task,originalRequest)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("NOTE_ALREADY_SAVED_REMOVED"));
        assertThatThrownBy(()->proposals.create(f.task,request(f))).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("NOTE_ALREADY_SAVED_REMOVED"));
        assertThat(count(f)).isEqualTo(1);
    }

    @Test void editedProposalInheritsLaterBoundaryAndRejectsUnsaveableOrRemovedSources() {
        var f=fixture();boundary(f,3);var id=propose(f);assertThat(row(id).requiredBoundary).isEqualTo(3);
        boundary(f,2);assertThat(confirm(id).httpStatus()).isEqualTo(409);
        var removed=fixture();var p=propose(removed);taskService.delete(removed.task,0);
        assertThat(confirm(p).httpStatus()).isEqualTo(409);assertThat(row(p).errorCode).isEqualTo("SOURCE_REMOVED");
        var failed=fixture();jdbc.update("UPDATE reading_task SET status='FAILED' WHERE id=?",failed.task);
        assertThatThrownBy(()->propose(failed)).isInstanceOf(ReadingException.class);
        var wrong=fixture();
        assertThatThrownBy(()->proposals.create(wrong.task,new ReadingNoteProposalService.Create(UUID.randomUUID(),"标题","正文",List.of("E999"),states.get(wrong.version).scopeRevision(),null)))
                .isInstanceOf(ReadingException.class);
        var other=fixture();
        jdbc.update("UPDATE reading_task_evidence SET source_json=(SELECT source_json FROM reading_task_evidence WHERE task_id=? LIMIT 1) WHERE task_id=?",other.task,wrong.task);
        assertThatThrownBy(()->propose(wrong)).isInstanceOf(ReadingException.class);
    }

    @Test void partialCharacterResultUnicodeLimitsAndStoredContentHashAreValidated() {
        var f=fixture();jdbc.update("UPDATE reading_task SET type='CHARACTER_LOOKUP',status='PARTIAL' WHERE id=?",f.task);
        var valid=new ReadingNoteProposalService.Create(UUID.randomUUID(),"人物查证","😀".repeat(5000),List.of(),states.get(f.version).scopeRevision(),null);
        var id=proposals.create(f.task,valid).id();assertThat(confirm(id).httpStatus()).isEqualTo(200);
        var n=noteMapper.byTask(states.owner(),f.task);
        assertThat(n.kind).isEqualTo("AI_CHARACTER");assertThat(ReadingText.length(n.body)).isEqualTo(5000);
        assertThat(n.userEdited).isTrue();assertThat(n.requiredBoundary).isEqualTo(2);
        var tooLong=fixture();
        assertThatThrownBy(()->proposals.create(tooLong.task,new ReadingNoteProposalService.Create(UUID.randomUUID(),"标题","😀".repeat(5001),List.of(),states.get(tooLong.version).scopeRevision(),null)))
                .isInstanceOf(ReadingException.class);
        var tampered=fixture();var p=propose(tampered);
        jdbc.update("UPDATE reading_note_proposal SET body='changed after preview' WHERE id=?",p);
        assertThat(confirm(p).httpStatus()).isEqualTo(409);assertThat(row(p).errorCode).isEqualTo("PREVIEW_CHANGED");
        assertThat(count(tampered)).isZero();
    }

    @Test void boundaryRollbackRacesConfirmationWithoutLeakingSavedContent() throws Exception {
        var f=fixture();var id=propose(f);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var save=pool.submit(()->{gate.await();return confirm(id);});
            var rollback=pool.submit(()->{gate.await();boundary(f,1);return true;});
            gate.countDown();var result=save.get(12,TimeUnit.SECONDS);rollback.get(12,TimeUnit.SECONDS);
            assertThat(result.httpStatus()).isIn(200,409);
        }
        assertThat(view(id)).doesNotContainKeys("body","title","contentHash","retainedEvidenceIds");
        var note=noteMapper.byTask(states.owner(),f.task);
        if(note!=null) {
            assertThat(notes.get(note.id).visibility()).isEqualTo("HIDDEN_BY_SCOPE");assertThat(notes.get(note.id).body()).isNull();
        } else assertThat(row(id).status).isEqualTo("FAILED");
    }

    @Test void httpContractRejectsUnknownFieldsAndPreservesScopeAndNoStore() throws Exception {
        var mvc=webAppContextSetup(web).build();var f=fixture();var request=request(f);
        var created=mvc.perform(post("/api/reading/tasks/"+f.task+"/note-proposals").contentType("application/json").content(ReadingText.json(request)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION")).andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(ReadingText.read(created,Map.class).get("id").toString());
        assertThat(count(f)).isZero();
        mvc.perform(post("/api/reading/note-proposals/"+id+"/confirm").contentType("application/json")
                .content(ReadingText.json(Map.of("contentHash",row(id).contentHash,"body","不能替换")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/reading/note-proposals/"+id+"/reject").contentType("application/json").content("{\"ownerId\":\"invalid\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/reading/versions/"+f.version+"/notes").contentType("application/json")
                .content(ReadingText.json(Map.of("clientRequestId",UUID.randomUUID(),"kind","AI_RECAP","title","标题","body","正文","expectedScopeRevision",states.get(f.version).scopeRevision()))))
                .andExpect(status().isBadRequest());
        assertThat(confirm(id).httpStatus()).isEqualTo(200);var note=noteMapper.byTask(states.owner(),f.task);
        mvc.perform(get("/api/reading/notes/"+note.id+"/citations/E1").param("revealTitle","true")).andExpect(status().isBadRequest());
        boundary(f,1);
        mvc.perform(get("/api/reading/note-proposals/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.body").doesNotExist());
        mvc.perform(get("/api/reading/notes/"+note.id+"/citations/E1")).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"));
        jdbc.update("UPDATE reading_book SET deleted=1 WHERE id=?",f.book);
        mvc.perform(get("/api/reading/note-proposals/"+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/reading/notes/"+note.id)).andExpect(status().isNotFound());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="reading.note.ui",matches="true")
    void offlineBrowserFixture() throws Exception {
        var desktop=fixture();var mobile=fixture();
        var metadata=java.nio.file.Path.of("target/reading-note-ui.json");
        var done=java.nio.file.Path.of("target/reading-note-ui.done");
        java.nio.file.Files.deleteIfExists(done);
        java.nio.file.Files.writeString(metadata,ReadingText.json(Map.of("port",port,"desktop",desktop,"mobile",mobile)));
        long until=System.nanoTime()+TimeUnit.MINUTES.toNanos(20);
        while(!java.nio.file.Files.exists(done) && System.nanoTime()<until)Thread.sleep(500);
        assertThat(java.nio.file.Files.exists(done)).as("Browser fixture must be explicitly closed").isTrue();
    }
}
