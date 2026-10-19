package io.github.fanqiepi.contextpilot.evaluation;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.chat.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** Independent DEBUG corpus only. Never awards frozen TEST acceptance. */
@SpringBootTest(properties={"spring.ai.model.chat=deepseek","spring.ai.model.embedding=openai",
        "spring.ai.vectorstore.type=pgvector","contextpilot.document.processing.enabled=true",
        "contextpilot.storage.root=target/refusal-debug-uploads","spring.ai.retry.max-attempts=1"})
@ActiveProfiles("local-secrets") @Testcontainers
@EnabledIfSystemProperty(named="contextpilot.evaluation.refusal-debug-live",matches="true")
class ChatRefusalDebugLiveTests {
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
    @Autowired ChatStreamingService streaming;
    @Autowired ConversationHistoryService history;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @Test @Timeout(600)
    void observesIndependentDebugQuestionsThroughBothProductionPaths() throws Exception {
        var report=new LinkedHashMap<String,Object>();
        report.put("mode","LIVE_DEBUG_NOT_ACCEPTANCE");report.put("startedAt",OffsetDateTime.now().toString());
        report.put("semanticReview","NOT_HUMAN_REVIEWED");
        var hashes=new TreeMap<String,String>();
        try(var files=Files.walk(Path.of("src/main"))){for(var file:files.filter(Files::isRegularFile).toList())
            hashes.put(file.toString().replace('\\','/'),sha(file));}
        for(String file:List.of("../evals/datasets/core-debug-v1.jsonl","../evals/corpora/core-debug-v1/solstice.md",
                "src/test/java/io/github/fanqiepi/contextpilot/evaluation/ChatRefusalDebugLiveTests.java"))hashes.put(file,sha(Path.of(file)));
        report.put("sourceHashes",hashes);
        var rows=new ArrayList<Map<String,Object>>();report.put("results",rows);
        Path output=Path.of("target/refusal-debug-live-"+UUID.randomUUID()+".json");
        UUID kb=bases.create(new KnowledgeBaseCreateRequest("Independent Solstice debug",null)).id();
        documents.upload(kb,new MockMultipartFile("file","solstice.md","text/markdown",
                Files.readAllBytes(Path.of("../evals/corpora/core-debug-v1/solstice.md"))));
        long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
        while(documents.list(kb).stream().anyMatch(d->d.status()!=DocumentStatus.SUCCEEDED)){
            assertThat(documents.list(kb)).noneMatch(d->d.status()==DocumentStatus.FAILED);
            assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(250);
        }
        for(String line:Files.readAllLines(Path.of("../evals/datasets/core-debug-v1.jsonl"))){
            if(line.isBlank())continue;
            var task=json.readTree(line);
            for(boolean stream:new boolean[]{false,true}){
                var row=new LinkedHashMap<String,Object>();rows.add(row);
                String trace="refusal-debug-"+UUID.randomUUID();
                row.put("caseId",task.path("id").asText());row.put("stream",stream);row.put("traceId",trace);
                row.put("expectedTerminal",task.path("terminal").asText());
                try {
                    var request=new ChatRequest(null,kb,task.path("question").asText());
                    if(stream){
                        var events=streaming.stream(request,trace).collectList().block(Duration.ofSeconds(100));
                        row.put("events",events.stream().map(e->Map.of("event",e.event(),"data",e.data())).toList());
                        var message=(ChatStreamPayload.Message)events.getFirst().data();
                        row.put("history",history.messages(message.conversationId()));
                        row.put("terminal",events.getLast().data() instanceof ChatStreamPayload.Done done ? done.status():"FAILED");
                    }else{
                        var answer=chat.answer(request,trace);row.put("answer",answer);
                        row.put("history",history.messages(answer.conversationId()));
                        row.put("terminal",answer.refused()?"REFUSED":"COMPLETED");
                    }
                }catch(Exception error){row.put("terminal","FAILED");row.put("errorType",error.getClass().getSimpleName());}
                row.put("modelCalls",jdbc.queryForList("SELECT model,prompt_version,status,prompt_tokens,completion_tokens,total_tokens FROM model_call WHERE trace_id=?",trace));
                Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            }
        }
        report.put("finishedAt",OffsetDateTime.now().toString());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(rows).hasSize(6);
        assertThat(rows).allSatisfy(row->assertThat(row.get("terminal")).isEqualTo(row.get("expectedTerminal")));
    }
    private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
