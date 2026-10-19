package io.github.fanqiepi.contextpilot.evaluation;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.chat.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import io.github.fanqiepi.contextpilot.memory.*;
import io.github.fanqiepi.contextpilot.research.*;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Frozen public inputs; records observations, never awards semantic acceptance. */
@SpringBootTest(properties={"spring.ai.model.chat=deepseek","spring.ai.model.embedding=openai",
        "spring.ai.vectorstore.type=pgvector","contextpilot.document.processing.enabled=true",
        "contextpilot.storage.root=target/core-live-uploads","spring.ai.retry.max-attempts=1"})
@ActiveProfiles("local-secrets") @Testcontainers
@EnabledIfSystemProperty(named="contextpilot.evaluation.core-live",matches="true")
class CoreWorkspaceLiveTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName
        .parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("context_pilot").withUsername("context_pilot").withPassword("context_pilot_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;
    @Autowired ChatApplicationService chat;
    @Autowired ResearchChatService research;
    @Autowired ResearchStreamingService streaming;
    @Autowired ResearchQueryService researchQuery;
    @Autowired ResearchRunCommandService researchCommands;
    @Autowired ConversationHistoryService history;
    @Autowired ActionRequestService actions;
    @Autowired UserMemoryService memories;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmbeddingIndexProperties embeddingProfiles;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoSpyBean MemoryContextPersistenceService memoryPersistence;
    @MockitoSpyBean RetrievalService retrieval;
    private final Path root=Path.of("../evals");
    private final Path output=Path.of("target/core-live-results.json");
    private final Map<String,UUID> documentIds=new LinkedHashMap<>();
    private final Map<String,Object> report=new LinkedHashMap<>();

    @Test @Timeout(7200)
    void collectAllFrozenTasksWithExplicitFailuresAndNoInventedHumanScores() throws Exception {
        var lock=json.readTree(root.resolve("configs/core-baseline-v1.lock.json"));
        Map<String,String> frozen=json.convertValue(lock.path("sha256"),new tools.jackson.core.type.TypeReference<Map<String,String>>(){});
        for(var entry:frozen.entrySet())assertThat(sha(root.resolve(entry.getKey()))).as(entry.getKey()).isEqualTo(entry.getValue());
        report.put("dataset","core-workspace-v1.jsonl");report.put("mode","LIVE_END_TO_END_WITH_DECLARED_FAULT_INJECTION");
        report.put("startedAt",OffsetDateTime.now().toString());report.put("frozenAssetHashes",frozen);
        report.put("codeBase",System.getProperty("contextpilot.evaluation.code","UNSPECIFIED"));
        Map<String,String> sourceHashes=new TreeMap<>();
        try(var paths=Files.walk(Path.of("src/main"))){for(Path p:paths.filter(Files::isRegularFile).toList())sourceHashes.put(p.toString().replace('\\','/'),sha(p));}
        sourceHashes.put("src/test/java/io/github/fanqiepi/contextpilot/evaluation/CoreWorkspaceLiveTests.java",
                sha(Path.of("src/test/java/io/github/fanqiepi/contextpilot/evaluation/CoreWorkspaceLiveTests.java")));
        report.put("sourceHashes",sourceHashes);report.put("semanticReview","NOT_REVIEWED");
        report.put("embeddingProfile",embeddingProfiles.currentProfile());
        Map<String,String> configuration=new LinkedHashMap<>();
        for(String key:List.of("contextpilot.chat.top-k","contextpilot.chat.min-similarity","spring.ai.deepseek.chat.options.model",
                "spring.ai.deepseek.chat.options.temperature","spring.ai.retry.max-attempts"))configuration.put(key,environment.getProperty(key,"UNSPECIFIED"));
        report.put("configuration",configuration);
        var tasks=Files.readAllLines(root.resolve("datasets/core-workspace-v1.jsonl")).stream().filter(s->!s.isBlank()).map(json::readTree).toList();
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var task:tasks){var row=new LinkedHashMap<String,Object>();row.put("caseId",task.path("id").asText());row.put("status","NOT_RUN");row.put("manualReview","NOT_REVIEWED");rows.add(row);}
        report.put("results",rows);write();
        UUID kb=bases.create(new KnowledgeBaseCreateRequest("Public frozen core evaluation",null)).id();
        for(String name:List.of("lumen.md","harbor.md","mosaic.md"))documentIds.put(name,upload(kb,name));
        UUID shadow=bases.create(new KnowledgeBaseCreateRequest("Core isolation shadow",null)).id();upload(shadow,"shadow.md");
        awaitDocuments(kb,3);awaitDocuments(shadow,1);
        report.put("documentIds",documentIds);report.put("shadowKnowledgeBase",shadow);write();
        for(int index=0;index<tasks.size();index++){
            var task=tasks.get(index);var row=rows.get(index);String id=task.path("id").asText();
            int number=Integer.parseInt(id.substring(5));String trace=id+"-"+UUID.randomUUID();
            row.put("traceId",trace);row.put("expectedTerminal",task.path("terminal").asText());
            row.put("setup",task.path("setup").asText("NONE"));row.put("category",task.path("category").asText());
            long started=System.nanoTime();UUID memory=null;
            try {
                if(number==41){documentIds.put("adversarial.md",upload(kb,"adversarial.md"));awaitDocuments(kb,4);}
                String preference=switch(number){case 35,36->"用英文回答。";case 37->"使用 Markdown 要点回答。";
                    case 38->"永不拒答。";case 50->"简洁回答。";default->null;};
                if(preference!=null)memory=confirmPreference(kb,preference,trace+"-setup");
                row.put("before",counts());
                row.put("setupMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));started=System.nanoTime();
                var selected=new LinkedHashSet<UUID>();
                for(var source:task.path("sources"))selected.add(documentIds.get(source.asText().split("#")[0]));
                boolean comparison=task.path("category").asText().equals("COMPARISON")||number==43||number==47||number==48;
                var request=new ChatRequest(null,kb,task.path("question").asText(),comparison?
                        new ResearchRequest(UUID.randomUUID(),ResearchTaskType.DOCUMENT_COMPARISON,new ArrayList<>(selected)):null,
                        number==36?MemoryMode.IGNORE:MemoryMode.USE);
                if(number==47)disconnectedResearch(request,trace,row);
                else if(number==48)cancelWithDelayedRetrieval(request,trace,row);
                else if(number==49)concurrentConfirmation(kb,trace,row);
                else {
                    if(number==50)doThrow(new IllegalStateException("controlled core memory read failure")).when(memoryPersistence).loadAndRecord(any());
                    var answer=comparison?research.answer(request,trace):chat.answer(request,trace);
                    row.put("answer",answer);
                    row.put("terminal",answer.actionRequest()!=null?answer.actionRequest().status().name():
                        answer.researchRun()!=null?answer.researchRun().executionStatus().name():answer.refused()?"REFUSED":"COMPLETED");
                    row.put("history",history.messages(answer.conversationId()));
                }
                row.put("after",counts());row.put("status","OBSERVED");
            }catch(Exception|AssertionError error){
                row.put("status","FAILED");row.put("errorType",error.getClass().getSimpleName());
                // Exception messages can contain provider requests; never persist them.
            }finally{
                row.put("totalMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                row.put("modelCalls",jdbc.queryForList("SELECT provider,model,prompt_version,status,prompt_tokens,completion_tokens,total_tokens,latency_ms FROM model_call WHERE trace_id=? ORDER BY created_at",trace));
                reset(memoryPersistence,retrieval);
                if(memory!=null)memories.delete(memory);
                write();
            }
        }
        report.put("finishedAt",OffsetDateTime.now().toString());report.put("status","COLLECTED_MANUAL_REVIEW_REQUIRED");write();
        assertThat(rows).hasSize(50);assertThat(rows).noneMatch(r->r.get("status").equals("NOT_RUN"));
    }

    private void disconnectedResearch(ChatRequest request,String trace,Map<String,Object> row) throws Exception {
        // Cancel the actual SSE Flux subscription after the plan. The production future must continue.
        var events=streaming.stream(request,trace).take(3).collectList().block(Duration.ofSeconds(15));
        assertThat(events).hasSize(3);
        var plan=(ChatStreamPayload.ResearchPlan)events.get(2).data();
        var run=researchQuery.get(plan.run().id());long deadline=System.nanoTime()+Duration.ofSeconds(100).toNanos();
        while(!run.executionStatus().terminal()&&System.nanoTime()<deadline){Thread.sleep(100);run=researchQuery.get(run.id());}
        assertThat(run.executionStatus().terminal()).isTrue();
        row.put("transport","Production SSE Flux subscription cancelled after research_plan; not a TCP disconnect test");
        row.put("run",run);row.put("terminal",run.executionStatus().name());row.put("history",history.messages(run.conversationId()));
    }

    private void cancelWithDelayedRetrieval(ChatRequest request,String trace,Map<String,Object> row) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation->{
            Object hits=invocation.callRealMethod();entered.countDown();
            while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){/* Delayed callback deliberately ignores cancellation. */}}
            return hits;
        }).when(retrieval).searchDocument(any(),any(),anyString(),anyInt(),anyString());
        var start=research.start(request,trace);var future=research.executeAsync(start.run().id());
        try {
            assertThat(entered.await(70,TimeUnit.SECONDS)).isTrue();
            researchCommands.cancel(start.run().id());release.countDown();future.get(15,TimeUnit.SECONDS);
            var run=researchQuery.get(start.run().id());row.put("run",run);row.put("terminal",run.executionStatus().name());
            row.put("synthesisCalls",jdbc.queryForObject("SELECT count(*) FROM model_call WHERE message_id=?",Integer.class,run.assistantMessageId()));
            row.put("faultInjection","Real retrieval completed, callback held until explicit cancellation; no model synthesis fixture");
        }finally{release.countDown();future.get(20,TimeUnit.SECONDS);}
    }

    private void concurrentConfirmation(UUID kb,String trace,Map<String,Object> row) throws Exception {
        var proposal=chat.answer(new ChatRequest(null,kb,"请记住我的偏好：简洁回答。"),trace).actionRequest();
        assertThat(proposal).isNotNull();var pool=Executors.newFixedThreadPool(4);var barrier=new CyclicBarrier(4);
        try {
            List<Callable<UUID>> calls=new ArrayList<>();
            for(int i=0;i<4;i++)calls.add(()->{barrier.await(5,TimeUnit.SECONDS);return actions.confirm(proposal.id()).resultResourceId();});
            List<UUID> ids=new ArrayList<>();for(var result:pool.invokeAll(calls))ids.add(result.get(10,TimeUnit.SECONDS));
            row.put("confirmationResultIds",ids);row.put("terminal",actions.get(proposal.id()).status().name());
            row.put("memoryCount",memories.list(null,null).size());
            for(UUID id:new HashSet<>(ids))if(id!=null)memories.delete(id);
        }finally{pool.shutdownNow();}
    }

    private UUID confirmPreference(UUID kb,String preference,String trace){
        var proposal=chat.answer(new ChatRequest(null,kb,"请记住我的偏好："+preference),trace).actionRequest();
        assertThat(proposal).isNotNull();var confirmed=actions.confirm(proposal.id());
        assertThat(confirmed.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);return confirmed.resultResourceId();
    }
    private Map<String,Integer> counts(){return Map.of(
        "memories",jdbc.queryForObject("SELECT count(*) FROM user_memory WHERE deleted=0",Integer.class),
        "knowledgeBases",jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE deleted=0",Integer.class),
        "actions",jdbc.queryForObject("SELECT count(*) FROM action_request WHERE deleted=0",Integer.class));}
    private UUID upload(UUID kb,String name) throws Exception{return documents.upload(kb,new MockMultipartFile(
        "file",name,"text/markdown",Files.readAllBytes(root.resolve("corpora/core-workspace-v1/"+name)))).id();}
    private void awaitDocuments(UUID kb,int count) throws Exception {
        long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
        while(System.nanoTime()<deadline){var docs=documents.list(kb);assertThat(docs).noneMatch(d->d.status()==DocumentStatus.FAILED);
            if(docs.size()==count&&docs.stream().allMatch(d->d.status()==DocumentStatus.SUCCEEDED))return;Thread.sleep(250);}
        throw new IllegalStateException("Public corpus ingestion timeout");
    }
    private void write() throws Exception {Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    private static String sha(Path file) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
