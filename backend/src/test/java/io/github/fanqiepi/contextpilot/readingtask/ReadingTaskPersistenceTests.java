package io.github.fanqiepi.contextpilot.readingtask;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none",
        "contextpilot.storage.root=target/reading-task-test-uploads","contextpilot.task-workspace.enabled=false","contextpilot.upgrade-research.enabled=false"})
@Import(ReadingTaskPersistenceTests.OfflineConfiguration.class)
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class ReadingTaskPersistenceTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @TestConfiguration static class OfflineConfiguration {
        @Bean @Primary FakeAi offlineReadingAi() { return new FakeAi(); }
    }
    static class FakeAi implements ReadingAiGateway {
        volatile ReadingProviderClient protocol;
        volatile ReadingLiveAuthorization live;
        volatile boolean allowed=true,blockEmbedding=false;volatile String finish="stop";volatile String answer="{\"facts\":[{\"text\":\"林舟来到渡口。\",\"evidenceIds\":[\"E1\"]}],\"partial\":false}";
        volatile String plan="{\"queries\":[\"林舟\",\"渡口\"]}";
        volatile CountDownLatch entered,release;
        final List<String> prompts=new CopyOnWriteArrayList<>();
        final AtomicInteger calls=new AtomicInteger(),embeddings=new AtomicInteger();
        public boolean offline(){return live==null;}
        public void requireAuthorized(){if(live!=null)live.check();if(!allowed)throw ReadingTaskPolicy.failure("LIVE_AI_NOT_AUTHORIZED");}
        public void authorizeWork(ReadingWork w,ReadingWork.Payload p){if(live!=null)live.authorize(w,p);}
        public Pricing pricing(String phase){return live.pricing(phase);}
        public int reservationInputTokens(String phase,int bytes){return live==null?bytes:ReadingLiveAuthorization.tokenBound(phase);}
        public void haltBatch(){if(live!=null)live.halt();}
        public void haltBatch(String reason){if(live!=null)live.halt(reason);}
        public Response generate(String prompt,int limit,Duration timeout) {
            if(protocol!=null)return protocol.generate(prompt,limit,timeout);
            requireAuthorized();calls.incrementAndGet();prompts.add(prompt);
            waitForRelease();
            return new Response(limit==512?plan:answer,12L,8L,finish);
        }
        void waitForRelease() {
            CountDownLatch gate=release,entry=entered;
            if(entry!=null)entry.countDown();
            if(gate!=null) {
                // Deliberately ignore cancellation: the late response must still be audited, never published.
                boolean done=false;while(!done)try{done=gate.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            }
        }
        public List<float[]> embed(List<String> texts,Duration timeout) {
            requireAuthorized();embeddings.incrementAndGet();
            if(blockEmbedding)waitForRelease();
            return texts.stream().map(t->{float[] v=new float[1024];v[0]=1;return v;}).toList();
        }
        public String generationInput(String prompt,int limit) { return protocol==null?prompt:protocol.generationInput(prompt,limit); }
        public EmbeddingResponse embedWithUsage(List<String> texts,Duration timeout) {
            return protocol==null?ReadingAiGateway.super.embedWithUsage(texts,timeout):protocol.embed(texts,timeout);
        }
        void block(){entered=new CountDownLatch(1);release=new CountDownLatch(1);}
        void unblock(){if(release!=null)release.countDown();release=null;entered=null;}
    }
    @Autowired FakeAi ai;
    @Autowired ReadingTaskService tasks;
    @Autowired ReadingTaskMapper mapper;
    @Autowired ReadingTaskQueue queue;
    @Autowired ReadingSpendService spend;
    @Autowired BookImportService imports;
    @Autowired BookParser parser;
    @Autowired BookService books;
    @Autowired BookMapper bookMapper;
    @Autowired ReadingStateService states;
    @Autowired ReadingScopeService scopes;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired WebApplicationContext web;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    record Fixture(UUID book,UUID version,List<BookRows.Paragraph> paragraphs) {}
    @BeforeEach void reset() { ai.live=null;ai.protocol=null;ai.allowed=true;ai.blockEmbedding=false;ai.finish="stop";ai.unblock();ai.prompts.clear();ai.calls.set(0);ai.embeddings.set(0);ai.answer="{\"facts\":[{\"text\":\"林舟来到渡口。\",\"evidenceIds\":[\"E1\"]}],\"partial\":false}"; }
    @AfterEach void unblock(){ai.unblock();}
    Fixture fixture(String body) {
        byte[] bytes=("第一章 渡口\n"+UUID.randomUUID()+"\n"+body+"\n第二章 秘密标题\n未读身份秘密").getBytes(StandardCharsets.UTF_8);
        var registration=imports.register(new BookImportService.Request(UUID.randomUUID(),"离线故事","自创工程夹具","TXT_CN_CHAPTER_V1",null,null),"story.txt",bytes);
        imports.parsed(registration.row().ownerId,registration.row().id,parser.parse(bytes,BookParser.Rule.TXT_CN_CHAPTER_V1,null));
        var confirmed=imports.confirm(registration.row().id,imports.preview(registration.row().id).previewHash());
        var ps=jdbc.query("SELECT id FROM book_paragraph WHERE version_id=? ORDER BY paragraph_ordinal",(rs,n)->bookMapper.paragraph(confirmed.versionId(),rs.getObject(1,UUID.class)),confirmed.versionId());
        return new Fixture(confirmed.bookId(),confirmed.versionId(),ps);
    }
    void boundary(Fixture f,int ordinal) {
        var state=states.get(f.version);states.boundary(f.version,new ReadingStateService.Boundary(UUID.randomUUID(),state.scopeRevision(),ordinal==0?null:f.paragraphs.get(ordinal-1).id(),true));
    }
    ReadingTaskService.Create recapRequest(Fixture f) {
        return new ReadingTaskService.Create(UUID.randomUUID(),f.version,states.get(f.version).scopeRevision(),"RECAP",3,null,null);
    }
    ReadingWork await(boolean index,UUID id,String... statuses) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<end) {
            var w=mapper.get(index,id);if(Arrays.asList(statuses).contains(w.status))return w;
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out: "+mapper.get(index,id).status+"/"+mapper.get(index,id).errorCode);
    }
    @SuppressWarnings("unchecked") Map<String,Object> view(UUID id){return (Map<String,Object>)tasks.get(false,id);}
    void index(Fixture f) throws Exception {
        var e=tasks.estimate(f.version,states.get(f.version).scopeRevision());
        var created=tasks.createIndex(f.version,new ReadingTaskService.IndexCreate(UUID.randomUUID(),states.get(f.version).scopeRevision(),e.estimateHash()));
        var w=await(true,created.id(),"READY","FAILED");assertThat(w.status).as(w.errorCode).isEqualTo("READY");
    }
    @Test void localHttpProtocolFlowsThroughIndexLookupUsageAndTruncationGate() throws Exception {
        var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var httpCalls=new AtomicInteger();
        var finish=new java.util.concurrent.atomic.AtomicReference<>("stop");
        http.createContext("/",exchange->{
            var request=json.readTree(exchange.getRequestBody().readAllBytes());httpCalls.incrementAndGet();String result;
            if(exchange.getRequestURI().getPath().equals("/embeddings")) {
                var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<request.path("input").size();i++) {
                    float[] vector=new float[1024];vector[0]=1;rows.add(Map.of("index",i,"embedding",vector));
                }
                result=ReadingText.json(Map.of("data",rows,"usage",Map.of("total_tokens",7)));
            } else result=ReadingText.json(Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content",ai.answer),"finish_reason",finish.get())),
                    "usage",Map.of("prompt_tokens",12,"completion_tokens",8)));
            byte[] data=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });http.start();
        String base="http://127.0.0.1:"+http.getAddress().getPort();
        ai.protocol=new ReadingProviderClient(java.net.URI.create(base+"/chat/completions"),java.net.URI.create(base+"/embeddings"),"fixture","fixture");
        try {
            var f=fixture("林舟来到渡口。\n后文秘密");boundary(f,2);index(f);
            var job=jdbc.queryForObject("SELECT id FROM reading_index_job WHERE version_id=?",UUID.class,f.version);
            assertThat(jdbc.queryForObject("SELECT usage_json->>'inputTokens' FROM reading_ai_call WHERE index_job_id=?",String.class,job)).isEqualTo("7");
            var created=tasks.create(new ReadingTaskService.Create(UUID.randomUUID(),f.version,states.get(f.version).scopeRevision(),"CHARACTER_LOOKUP",null,"林舟是谁","DIRECT"));
            assertThat(await(false,created.id(),"COMPLETED","FAILED").status).isEqualTo("COMPLETED");
            assertThat(httpCalls).hasValue(3); // One batch, one query embedding, one answer.
            assertThat(jdbc.queryForObject("SELECT usage_json->>'inputTokens' FROM reading_ai_call WHERE task_id=? AND phase='SEARCH_0'",String.class,created.id())).isEqualTo("7");
            assertThat(jdbc.queryForObject("SELECT usage_json->>'outputTokens' FROM reading_ai_call WHERE task_id=? AND phase='SEARCH_0'",String.class,created.id())).isEqualTo("0");
            var payload=ReadingTaskService.payload(mapper.get(false,created.id()));
            int wireBytes=ReadingTaskPolicy.bytes(ai.generationInput(ReadingTaskPolicy.prompt(payload,false),2048));
            assertThat(jdbc.queryForObject("SELECT input_bytes FROM reading_ai_call WHERE task_id=? AND phase='ANSWER'",Integer.class,created.id())).isEqualTo(wireBytes);
            finish.set("length");var truncated=tasks.create(recapRequest(f));
            assertThat(await(false,truncated.id(),"FAILED").errorCode).isEqualTo("OUTPUT_INVALID");
            assertThat(view(truncated.id()).get("validatedResult")).isNull();
            assertThat(jdbc.queryForObject("SELECT usage_json->>'outputTokens' FROM reading_ai_call WHERE task_id=?",String.class,truncated.id())).isEqualTo("8");
            assertThat(httpCalls).hasValue(4);
        } finally { ai.protocol=null;http.stop(0); }
    }
    @Test void recapPersistsCompleteOrderedEvidenceAndCitationWithoutUnreadTextOrPositionWrites() throws Exception {
        var f=fixture("林舟来到渡口。\n青禾递给他一封信。");boundary(f,2);
        var request=recapRequest(f);var created=tasks.create(request);
        var w=await(false,created.id(),"COMPLETED","FAILED");assertThat(w.status).as(w.errorCode).isEqualTo("COMPLETED");
        assertThat(tasks.create(request).id()).isEqualTo(created.id());assertThat(ai.calls).hasValue(1);
        assertThat(ai.prompts.getFirst()).contains("林舟来到渡口").doesNotContain("青禾递","秘密标题","未读身份秘密");
        assertThat(ReadingText.json(tasks.citation(created.id(),"E1"))).contains("林舟来到渡口").doesNotContain("青禾递","渡口\"");
        assertThat(states.get(f.version).resumePosition()).isNull();
        assertThatThrownBy(()->tasks.citation(created.id(),"E99")).isInstanceOf(ReadingException.class);
        boundary(f,1);
        assertThat(view(created.id())).containsEntry("visibility","HIDDEN_BY_SCOPE").doesNotContainKeys("question","validatedResult","scope");
        assertThatThrownBy(()->tasks.citation(created.id(),"E1")).isInstanceOf(ReadingException.class);
        boundary(f,2);assertThat(view(created.id()).get("validatedResult")).isNotNull();
    }
    @Test void pgvectorCandidatesFilterBeforeTopKAndLookupUsesOnlyCurrentPrefix() throws Exception {
        var f=fixture("林舟来到渡口。\n以后才揭露的别名");boundary(f,f.paragraphs.size());index(f);
        boundary(f,2);
        var scope=scopes.capture(f.version);var p=new ReadingWork.Payload();p.scope=scope;
        float[] vector=new float[1024];vector[0]=1;
        var candidates=mapper.search(scope.ownerId(),f.book,f.version,2,p.profile,ReadingTaskPolicy.vector(vector));
        assertThat(candidates).hasSize(2).allMatch(s->s.paragraphOrdinal()<=2);
        var request=new ReadingTaskService.Create(UUID.randomUUID(),f.version,states.get(f.version).scopeRevision(),"CHARACTER_LOOKUP",null,"林舟是谁","MULTI_QUERY");
        var created=tasks.create(request);var w=await(false,created.id(),"COMPLETED","FAILED");
        assertThat(w.status).as(w.errorCode).isEqualTo("COMPLETED");
        var state=ReadingTaskService.payload(w);assertThat(state.searches).isEqualTo(2);assertThat(state.modelCalls).isEqualTo(2);
        assertThat(state.evidence).hasSize(2);assertThat(String.join("",ai.prompts)).doesNotContain("以后才","未读身份秘密","秘密标题");
        assertThat(mapper.search(scope.ownerId(),UUID.randomUUID(),f.version,2,p.profile,ReadingTaskPolicy.vector(vector))).isEmpty();
        assertThat(mapper.search(scope.ownerId(),f.book,f.version,2,"wrong-profile",ReadingTaskPolicy.vector(vector))).isEmpty();
    }
    @Test void rollbackThenForwardInvalidatesInFlightPublicationButAuditsLateUsage() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);ai.block();
        var created=tasks.create(recapRequest(f));assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();
        boundary(f,1);boundary(f,2);tasks.sweep();
        assertThat(mapper.get(false,created.id()).status).isEqualTo("INVALIDATED");
        ai.unblock();Thread.sleep(300);
        assertThat(view(created.id()).get("validatedResult")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_ai_call WHERE task_id=? AND status='SUCCEEDED'",Integer.class,created.id())).isEqualTo(1);
    }
    @Test void pauseUnknownCallCannotResumeOrPublishAndCancellationIsIdempotent() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);ai.block();
        var created=tasks.create(recapRequest(f));assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();
        var running=mapper.get(false,created.id());
        var pause=new ReadingTaskService.Command(UUID.randomUUID(),running.revision,"PAUSE");
        tasks.command(false,created.id(),pause);tasks.command(false,created.id(),pause);
        var paused=mapper.get(false,created.id());assertThat(paused.status).isEqualTo("PAUSED");assertThat(paused.activeMillis).isGreaterThan(0);
        assertThatThrownBy(()->tasks.command(false,created.id(),new ReadingTaskService.Command(UUID.randomUUID(),paused.revision,"RESUME")))
                .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("INTERRUPTED_CALL"));
        tasks.command(false,created.id(),new ReadingTaskService.Command(UUID.randomUUID(),paused.revision,"CANCEL"));
        ai.unblock();Thread.sleep(300);assertThat(mapper.get(false,created.id()).status).isEqualTo("CANCELLED");
        assertThat(view(created.id()).get("validatedResult")).isNull();
    }
    @Test void queueFullCreatesNoHiddenJobAndTimeoutDoesNotBecomeSemanticRefusal() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);ai.block();
        var one=tasks.create(recapRequest(f));assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();
        var two=tasks.create(recapRequest(f));var three=tasks.create(recapRequest(f));
        var rejected=recapRequest(f);
        assertThatThrownBy(()->tasks.create(rejected)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("QUEUE_FULL"));
        assertThat(mapper.replay(false,states.owner(),rejected.clientRequestId())).isNull();
        jdbc.update("UPDATE reading_task SET queued_at=CURRENT_TIMESTAMP-INTERVAL '31 seconds' WHERE id IN (?,?)",two.id(),three.id());
        jdbc.update("UPDATE reading_task SET active_millis=90000 WHERE id=?",one.id());tasks.sweep();
        assertThat(mapper.get(false,two.id()).errorCode).isEqualTo("QUEUE_TIMEOUT");
        assertThat(mapper.get(false,one.id()).status).isEqualTo("FAILED");ai.unblock();Thread.sleep(400);
    }
    @Test void restartPausesAndBudgetSurvivesSafeResumeWithoutRegenerating() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);
        // Hold the worker so a second job remains queued at a safe checkpoint.
        ai.block();var blocker=tasks.create(recapRequest(f));assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();
        var queued=tasks.create(recapRequest(f));tasks.recover();
        var paused=mapper.get(false,queued.id());assertThat(paused.status).isEqualTo("PAUSED");assertThat(paused.errorCode).isEqualTo("RESTARTED");
        ai.unblock();Thread.sleep(300);
        tasks.command(false,queued.id(),new ReadingTaskService.Command(UUID.randomUUID(),paused.revision,"RESUME"));
        assertThat(await(false,queued.id(),"COMPLETED","FAILED").status).isEqualTo("COMPLETED");
        var b=mapper.get(false,blocker.id());tasks.command(false,b.id,new ReadingTaskService.Command(UUID.randomUUID(),b.revision,"CANCEL"));
    }
    @Test void oversizeCoverageAndInvalidModelOutputFailBeforePublication() throws Exception {
        var f=fixture("字".repeat(25000));boundary(f,f.paragraphs.size()-1);
        assertThatThrownBy(()->tasks.create(recapRequest(f))).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("COVERAGE_LIMIT"));
        assertThat(ai.calls).hasValue(0);
        var small=fixture("林舟来到渡口。");boundary(small,2);ai.answer="{\"facts\":[{\"text\":\"伪造\",\"evidenceIds\":[\"E999\"]}]}";
        var created=tasks.create(recapRequest(small));var failed=await(false,created.id(),"FAILED");
        assertThat(failed.errorCode).isEqualTo("OUTPUT_INVALID");assertThat(view(created.id()).get("validatedResult")).isNull();
    }
    @Test void indexEstimationStopsAtCompleteParagraphAndReusesPaidSegments() throws Exception {
        var f=fixture(("字".repeat(3999)+"\n").repeat(30));boundary(f,f.paragraphs.size()-1);
        var e=tasks.estimate(f.version,states.get(f.version).scopeRevision());
        assertThat(e.characters()).isLessThanOrEqualTo(100000);assertThat(e.targetBoundary()).isLessThan(e.requestedBoundary());
        index(f);var next=tasks.estimate(f.version,states.get(f.version).scopeRevision());
        assertThat(next.fromBoundary()).isEqualTo(e.targetBoundary());index(f);
        var all=tasks.estimate(f.version,states.get(f.version).scopeRevision());assertThat(all.segments()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_index_segment WHERE version_id=?",Integer.class,f.version)).isEqualTo(121);
    }
    @Test void disabledProviderHttpRejectsUnknownFieldsAndHistoryHasNoStore() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);ai.allowed=false;
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        mvc.perform(post("/api/reading/tasks").contentType("application/json").content(ReadingText.json(recapRequest(f))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIVE_AI_NOT_AUTHORIZED"));
        mvc.perform(post("/api/reading/tasks").contentType("application/json").content("{\"ownerId\":\"forged\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reading/tasks").param("versionId",f.version.toString())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(ai.calls).hasValue(0);assertThat(ai.embeddings).hasValue(0);
    }
    @Test void monetaryReservationIsAtomicAndUnknownUsageDoesNotReleaseBudget() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);
        var task=tasks.create(recapRequest(f));await(false,task.id(),"COMPLETED");
        UUID authorization=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        jdbc.update("INSERT INTO reading_spend_ledger(id,owner_id,currency,limit_amount,pricing_version,expires_at) VALUES(?,?,'CNY',1,'offline-price-fixture',CURRENT_TIMESTAMP+INTERVAL '1 hour')",authorization,states.owner());
        mapper.call(a,task.id(),null,UUID.randomUUID(),2,"TEST_A",60,60,0);
        mapper.call(b,task.id(),null,UUID.randomUUID(),3,"TEST_B",60,60,0);
        var pricing=new ReadingAiGateway.Pricing(authorization,"offline-price-fixture",new java.math.BigDecimal("0.01"),java.math.BigDecimal.ZERO);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=pool.invokeAll(List.<Callable<Boolean>>of(()->{try{spend.reserve(states.owner(),a,pricing,60,0);return true;}catch(ReadingException e){return false;}},
                    ()->{try{spend.reserve(states.owner(),b,pricing,60,0);return true;}catch(ReadingException e){return false;}}));
            assertThat(results.stream().filter(future->{try{return future.get();}catch(Exception e){throw new RuntimeException(e);}}).count()).isEqualTo(1);
        }
        UUID used=mapper.callRow(a).get("authorization_id")!=null?a:b;
        spend.settle(states.owner(),used,null);
        assertThat(jdbc.queryForObject("SELECT reserved_amount FROM reading_spend_ledger WHERE id=?",java.math.BigDecimal.class,authorization)).isEqualByComparingTo("0.6");
        spend.settle(states.owner(),used,new ReadingAiGateway.Response("",10L,0L));
        spend.settle(states.owner(),used,new ReadingAiGateway.Response("",10L,0L));
        assertThat(jdbc.queryForObject("SELECT settled_amount FROM reading_spend_ledger WHERE id=?",java.math.BigDecimal.class,authorization)).isEqualByComparingTo("0.1");
        assertThat(jdbc.queryForObject("SELECT reserved_amount FROM reading_spend_ledger WHERE id=?",java.math.BigDecimal.class,authorization)).isZero();
        mapper.freezeSpend(authorization);
        assertThatThrownBy(()->spend.reserve(states.owner(),used.equals(a)?b:a,pricing,60,0)).isInstanceOf(ReadingException.class);
    }
    @Test void liveCallCapUnknownFreezeAndReinstallationCannotResetBudget() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);var task=tasks.create(recapRequest(f));await(false,task.id(),"COMPLETED");
        UUID authorization=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();String digest=ReadingText.hash(authorization.toString());
        mapper.installLive(authorization,states.owner(),new java.math.BigDecimal("20"),ReadingLiveBatch.PRICE,java.time.OffsetDateTime.now().plusHours(1),digest,1);
        mapper.call(a,task.id(),null,UUID.randomUUID(),2,"TEST_A",60,60,0);mapper.call(b,task.id(),null,UUID.randomUUID(),3,"TEST_B",60,60,0);
        var pricing=new ReadingAiGateway.Pricing(authorization,ReadingLiveBatch.PRICE,ReadingLiveBatch.INPUT,java.math.BigDecimal.ZERO);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=pool.invokeAll(List.<Callable<Boolean>>of(()->{try{spend.reserve(states.owner(),a,pricing,1048576,0);return true;}catch(ReadingException e){return false;}},
                    ()->{try{spend.reserve(states.owner(),b,pricing,1048576,0);return true;}catch(ReadingException e){return false;}}));
            assertThat(results.stream().filter(x->{try{return x.get();}catch(Exception e){throw new RuntimeException(e);}}).count()).isEqualTo(1);
        }
        UUID used=mapper.callRow(a).get("authorization_id")!=null?a:b;
        assertThatThrownBy(()->spend.reserve(states.owner(),used,pricing,1048576,0)).isInstanceOf(ReadingException.class);
        spend.settle(states.owner(),used,null);
        assertThat(mapper.liveAvailable(authorization,states.owner(),digest)).isZero();
        mapper.installLive(authorization,states.owner(),new java.math.BigDecimal("20"),ReadingLiveBatch.PRICE,java.time.OffsetDateTime.now().plusHours(1),digest,1);
        assertThat(mapper.liveAvailable(authorization,states.owner(),digest)).isZero();
        assertThat(jdbc.queryForObject("SELECT calls_started FROM reading_spend_ledger WHERE id=?",Integer.class,authorization)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_amount FROM reading_spend_ledger WHERE id=?",java.math.BigDecimal.class,authorization)).isEqualByComparingTo("2.097152");
    }
    @Test void authorizedTaskUsesDurableBudgetAndReplayCannotGenerateTwice() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);var request=recapRequest(f);var version=states.version(states.owner(),f.version);
        var entry=new ReadingLiveBatch.Entry("debug-001",1,"DEBUG",f.version,f.book,version.fileHash(),version.parserId(),2,"RECAP",null,null,3,1,2,
                f.paragraphs.stream().map(p->new ReadingLiveBatch.Paragraph(p.id(),p.paragraphOrdinal(),p.textHash())).toList());
        UUID authorization=UUID.randomUUID();String digest=ReadingText.hash(authorization.toString());
        var batch=new ReadingLiveBatch(authorization,digest,"plan","lock",new java.math.BigDecimal("20"),java.time.OffsetDateTime.now().minusMinutes(1),
                java.time.OffsetDateTime.now().plusHours(1),Map.of(request.clientRequestId(),entry));
        ai.live=new ReadingLiveAuthorization(mapper,bookMapper,states,batch,false);
        var registered=tasks.create(request);assertThat(await(false,registered.id(),"COMPLETED","FAILED").status).isEqualTo("COMPLETED");
        assertThat(tasks.create(request).id()).isEqualTo(registered.id());assertThat(ai.calls).hasValue(1);
        assertThatThrownBy(()->tasks.create(recapRequest(f))).isInstanceOf(ReadingException.class);
        assertThat(jdbc.queryForObject("SELECT calls_started FROM reading_spend_ledger WHERE id=?",Integer.class,authorization)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT settled_amount FROM reading_spend_ledger WHERE id=?",java.math.BigDecimal.class,authorization)).isEqualByComparingTo("0.000088");
        assertThat(jdbc.queryForObject("SELECT input_token_bound FROM reading_ai_call WHERE task_id=?",Integer.class,registered.id())).isEqualTo(1048576);
        assertThat(view(registered.id())).containsKey("elapsedMillis");
    }
    @Test void budgetRefusalStopsBatchBeforeAnyProviderInvocation() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);var request=recapRequest(f);var version=states.version(states.owner(),f.version);
        var entry=new ReadingLiveBatch.Entry("debug-001",1,"DEBUG",f.version,f.book,version.fileHash(),version.parserId(),2,"RECAP",null,null,3,1,2,
                f.paragraphs.stream().map(p->new ReadingLiveBatch.Paragraph(p.id(),p.paragraphOrdinal(),p.textHash())).toList());
        UUID authorization=UUID.randomUUID();String digest=ReadingText.hash(authorization.toString());
        var batch=new ReadingLiveBatch(authorization,digest,"plan","lock",java.math.BigDecimal.ONE,java.time.OffsetDateTime.now().minusMinutes(1),
                java.time.OffsetDateTime.now().plusHours(1),Map.of(request.clientRequestId(),entry));
        ai.live=new ReadingLiveAuthorization(mapper,bookMapper,states,batch,false);
        var registered=tasks.create(request);assertThat(await(false,registered.id(),"FAILED").errorCode).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(ai.calls).hasValue(0);assertThat(ai.embeddings).hasValue(0);
        assertThat(mapper.calls(registered.id())).isEmpty();
        assertThat(mapper.ledger(authorization,states.owner())).containsEntry("stop_reason","BUDGET_EXHAUSTED").containsEntry("calls_started",0);
        assertThat(ai.live.status()).containsEntry("liveAllowed",false).containsEntry("stopReason","BUDGET_EXHAUSTED");
        assertThatThrownBy(ai.live::check).isInstanceOf(ReadingException.class);
        assertThat(tasks.create(request).id()).isEqualTo(registered.id());assertThat(ai.calls).hasValue(0);
    }
    @Test void providerBudgetsAreIndependentAndEitherStoppedLedgerBlocksBoth() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);var task=tasks.create(recapRequest(f));await(false,task.id(),"COMPLETED");
        UUID authorization=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        var batch=new ReadingLiveBatch(authorization,ReadingText.hash(authorization.toString()),"plan","lock",new java.math.BigDecimal("20"),new java.math.BigDecimal("10"),
                java.time.OffsetDateTime.now().minusMinutes(1),java.time.OffsetDateTime.now().plusHours(1),Map.of());
        var gate=new ReadingLiveAuthorization(mapper,bookMapper,states,batch,false);gate.check();
        mapper.call(a,task.id(),null,UUID.randomUUID(),2,"INDEX_0",1,1,0);mapper.call(b,task.id(),null,UUID.randomUUID(),3,"INDEX_1",1,1,0);
        var price=new ReadingAiGateway.Pricing(gate.pricing("INDEX_0").authorizationId(),ReadingLiveBatch.PRICE,java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO);
        spend.reserve(states.owner(),a,price,8,0);
        assertThatThrownBy(()->spend.reserve(states.owner(),b,price,3,0)).isInstanceOf(ReadingException.class);
        assertThat(mapper.ledger(batch.id,states.owner())).containsEntry("warning_emitted",false).containsEntry("calls_started",0);
        assertThat(mapper.ledger(batch.embeddingId(),states.owner())).containsEntry("warning_emitted",true).containsEntry("calls_started",1);
        assertThat((java.math.BigDecimal)mapper.ledger(batch.id,states.owner()).get("reserved_amount")).isZero();
        assertThat(gate.status()).containsEntry("callsStarted",1L).containsEntry("limitCny",new java.math.BigDecimal("30"));
        spend.settle(states.owner(),a,null);
        assertThatThrownBy(()->gate.pricing("ANSWER")).isInstanceOf(ReadingException.class);
        assertThat(gate.status()).containsEntry("liveAllowed",false).containsEntry("stopReason","USAGE_UNKNOWN");
    }
    @Test void thresholdWarningCommitsOnceAndSurvivesRepeatedInstall() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);var task=tasks.create(recapRequest(f));await(false,task.id(),"COMPLETED");
        UUID authorization=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();String digest=ReadingText.hash(authorization.toString());
        mapper.installLive(authorization,states.owner(),java.math.BigDecimal.ONE,ReadingLiveBatch.PRICE,java.time.OffsetDateTime.now().plusHours(1),digest,10);
        mapper.call(a,task.id(),null,UUID.randomUUID(),2,"TEST_A",80,80,0);mapper.call(b,task.id(),null,UUID.randomUUID(),3,"TEST_B",1,1,0);
        var price=new ReadingAiGateway.Pricing(authorization,ReadingLiveBatch.PRICE,new java.math.BigDecimal("0.01"),java.math.BigDecimal.ZERO);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ReadingBudgetLog.class);
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{spend.reserve(states.owner(),a,price,80,0);tx.setRollbackOnly();});
            assertThat(capture.list).isEmpty();assertThat(mapper.ledger(authorization,states.owner())).containsEntry("warning_emitted",false);
            spend.reserve(states.owner(),a,price,80,0);spend.settle(states.owner(),a,new ReadingAiGateway.Response("NEVER_LOG_THIS_BODY",80L,0L));
            mapper.installLive(authorization,states.owner(),java.math.BigDecimal.ONE,ReadingLiveBatch.PRICE,java.time.OffsetDateTime.now().plusHours(1),digest,10);
            spend.reserve(states.owner(),b,price,1,0);
            assertThat(mapper.ledger(authorization,states.owner())).containsEntry("warning_emitted",true);
            assertThat(capture.list.stream().map(e->e.getFormattedMessage()).filter(m->m.contains("READING_BUDGET_WARNING")).count()).isEqualTo(1);
            assertThat(capture.list.toString()).doesNotContain("NEVER_LOG_THIS_BODY","林舟");
        } finally {logger.detachAppender(capture);capture.stop();}
    }
    @Test void exceededUsageRetainsReservationAndFirstStopReason() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);var task=tasks.create(recapRequest(f));await(false,task.id(),"COMPLETED");
        UUID authorization=UUID.randomUUID(),call=UUID.randomUUID();String digest=ReadingText.hash(authorization.toString());
        mapper.installLive(authorization,states.owner(),java.math.BigDecimal.ONE,ReadingLiveBatch.PRICE,java.time.OffsetDateTime.now().plusHours(1),digest,10);
        mapper.call(call,task.id(),null,UUID.randomUUID(),2,"TEST_A",50,50,0);
        var price=new ReadingAiGateway.Pricing(authorization,ReadingLiveBatch.PRICE,new java.math.BigDecimal("0.01"),java.math.BigDecimal.ZERO);
        spend.reserve(states.owner(),call,price,50,0);spend.settle(states.owner(),call,new ReadingAiGateway.Response("",51L,0L));
        mapper.freezeSpend(authorization);
        assertThat(mapper.ledger(authorization,states.owner())).containsEntry("stop_reason","TOKEN_BOUND_EXCEEDED");
        assertThat(mapper.liveAvailable(authorization,states.owner(),digest)).isZero();
        assertThat(mapper.callRow(call).get("settled_amount")).isNull();
        assertThat((java.math.BigDecimal)mapper.ledger(authorization,states.owner()).get("reserved_amount")).isEqualByComparingTo("0.5");
    }
    @Test void exactlySpentLimitStopsWithoutPermittingAnotherCall() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);var task=tasks.create(recapRequest(f));await(false,task.id(),"COMPLETED");
        UUID authorization=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();String digest=ReadingText.hash(authorization.toString());
        mapper.installLive(authorization,states.owner(),java.math.BigDecimal.ONE,ReadingLiveBatch.PRICE,java.time.OffsetDateTime.now().plusHours(1),digest,10);
        mapper.call(a,task.id(),null,UUID.randomUUID(),2,"TEST_A",100,100,0);mapper.call(b,task.id(),null,UUID.randomUUID(),3,"TEST_B",1,1,0);
        var price=new ReadingAiGateway.Pricing(authorization,ReadingLiveBatch.PRICE,new java.math.BigDecimal("0.01"),java.math.BigDecimal.ZERO);
        spend.reserve(states.owner(),a,price,100,0);
        assertThat(mapper.liveAvailable(authorization,states.owner(),digest)).isEqualTo(1); // Reserved request may still execute.
        spend.settle(states.owner(),a,new ReadingAiGateway.Response("",100L,0L));
        assertThat(mapper.ledger(authorization,states.owner())).containsEntry("stop_reason","BUDGET_EXHAUSTED");
        assertThatThrownBy(()->spend.reserve(states.owner(),b,price,1,0)).isInstanceOf(ReadingException.class);
    }
    @Test void evidenceAndVectorWritesRollBackOnDatabaseFailure() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);
        jdbc.execute("CREATE FUNCTION reading_task_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER reading_task_test_failure BEFORE INSERT ON reading_vector_store FOR EACH ROW EXECUTE FUNCTION reading_task_test_fail()");
        try {
            var estimate=tasks.estimate(f.version,states.get(f.version).scopeRevision());
            var job=tasks.createIndex(f.version,new ReadingTaskService.IndexCreate(UUID.randomUUID(),states.get(f.version).scopeRevision(),estimate.estimateHash()));
            assertThat(await(true,job.id(),"FAILED").errorCode).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_index_segment WHERE version_id=?",Integer.class,f.version)).isZero();
            assertThat(mapper.readyBoundary(f.version,2,new ReadingWork.Payload().profile)).isZero();
        } finally {jdbc.execute("DROP TRIGGER reading_task_test_failure ON reading_vector_store");jdbc.execute("DROP FUNCTION reading_task_test_fail()");}
        index(f);assertThat(mapper.readyBoundary(f.version,2,new ReadingWork.Payload().profile)).isEqualTo(2);
    }
    @Test void lateEmbeddingNeverPublishesAfterScopeRollback() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);ai.blockEmbedding=true;ai.block();
        var e=tasks.estimate(f.version,states.get(f.version).scopeRevision());
        var job=tasks.createIndex(f.version,new ReadingTaskService.IndexCreate(UUID.randomUUID(),states.get(f.version).scopeRevision(),e.estimateHash()));
        assertThat(ai.entered.await(5,TimeUnit.SECONDS)).isTrue();
        boundary(f,1);boundary(f,2);tasks.sweep();ai.unblock();Thread.sleep(300);
        assertThat(mapper.get(true,job.id()).status).isEqualTo("INVALIDATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_index_segment WHERE generation=?",Integer.class,job.id())).isZero();
    }
    @Test void sseReconnectRebuildsHiddenViewAndTruncationIsTechnicalFailure() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);
        var completed=tasks.create(recapRequest(f));await(false,completed.id(),"COMPLETED");boundary(f,1);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        var result=mvc.perform(get("/api/reading/tasks/"+completed.id()+"/events").header("Last-Event-ID",completed.id()+"/0"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("event:done") && System.nanoTime()<until)Thread.sleep(25);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("HIDDEN_BY_SCOPE","snapshot").doesNotContain("林舟","validatedResult","question","event:result");
        boundary(f,2);ai.finish="length";
        var truncated=tasks.create(recapRequest(f));assertThat(await(false,truncated.id(),"FAILED").errorCode).isEqualTo("OUTPUT_INVALID");
        assertThat(view(truncated.id()).get("validatedResult")).isNull();
    }
    @Test void invalidationSweepPagesWithoutSkippingRowsWhoseStatusChanges() throws Exception {
        var f=fixture("林舟来到渡口。");boundary(f,2);
        var original=tasks.create(recapRequest(f));await(false,original.id(),"COMPLETED");
        jdbc.update("""
                INSERT INTO reading_task(id,owner_id,book_id,version_id,client_request_id,request_hash,type,status,
                  required_boundary,payload_json,trace_id)
                SELECT gen_random_uuid(),owner_id,book_id,version_id,gen_random_uuid(),request_hash,type,'PAUSED',
                  required_boundary,payload_json,gen_random_uuid() FROM reading_task CROSS JOIN generate_series(1,105) WHERE id=?
                """,original.id());
        boundary(f,1);tasks.sweep();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_task WHERE version_id=? AND status='INVALIDATED'",Integer.class,f.version)).isEqualTo(105);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="reading.ui",matches="true")
    void offlineBrowserFixture() throws Exception {
        var f=fixture("林舟来到渡口。\n青禾递给他一封信。");boundary(f,2);index(f);
        var recap=tasks.create(recapRequest(f));await(false,recap.id(),"COMPLETED");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/reading-task-ui.json"),
                ReadingText.json(Map.of("port",port,"bookId",f.book,"versionId",f.version,"taskId",recap.id(),
                        "firstParagraphId",f.paragraphs.getFirst().id(),"secondParagraphId",f.paragraphs.get(1).id())));
        for(int i=0;i<1800;i++) {
            if(java.nio.file.Files.exists(java.nio.file.Path.of("target/reading-task-ui.done")))return;
            Thread.sleep(1000);
        }
    }
}
