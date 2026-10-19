package io.github.fanqiepi.contextpilot.researchreview;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import io.github.fanqiepi.contextpilot.taskworkspace.TaskState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static io.github.fanqiepi.contextpilot.researchreview.ReviewData.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit provider opt-in; synthetic public corpus, no private task content. */
@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none"})
@ActiveProfiles("local-secrets")
@Testcontainers
@EnabledIfSystemProperty(named="contextpilot.evaluation.review-live",matches="true")
class ReviewLiveTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Value("${spring.ai.deepseek.api-key:}") String key;
    @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-flash}") String modelName;
    record Case(String id,String goal,String answer,String evidence,String expected) { }
    @Test @Timeout(780) void fourPublicCategoriesTwiceWithRawResults() throws Exception {
        var root=JSON.readTree(Files.readString(Path.of("../evals/cases/research-review-v1.json")));
        var cases=JSON.convertValue(root.get("cases"),Case[].class);
        var gateway=new ReviewModelGateway(key,modelName);var rows=new ArrayList<Map<String,Object>>();
        var executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"review-live");t.setDaemon(true);return t;});
        try {
            for(var c:cases) for(int repeat=1;repeat<=2;repeat++) {
                UUID doc=UUID.randomUUID();var row=new LinkedHashMap<String,Object>();rows.add(row);
                var snapshot=new Snapshot(UUID.randomUUID(),1,1,UUID.randomUUID(),c.goal(),List.of(),c.answer(),
                        List.of(new TaskState.Source(doc,"public-demo.md","public","fixture","2026-10-19T00:00:00Z")),
                        List.of(new TaskState.Evidence("E1",doc,"public-demo.md","public-chunk",null,c.evidence())));
                row.put("caseId",c.id());row.put("repeat",repeat);row.put("expected",c.expected());row.put("snapshot",snapshot);
                row.put("startedAt",OffsetDateTime.now().toString());row.put("promptVersion","research-review-v1");
                var future=executor.submit(()->gateway.call(snapshot));
                try {
                    var response=future.get(90,TimeUnit.SECONDS);row.put("raw",response.text());row.put("usage",response.call());
                    var result=ReviewValidator.result(response.text(),snapshot);row.put("result",result);row.put("overall",ReviewValidator.overall(result));row.put("execution","COMPLETED");
                } catch(Exception error) {row.put("execution","FAILED");row.put("errorType",error.getClass().getSimpleName());}
                finally {future.cancel(true);row.put("finishedAt",OffsetDateTime.now().toString());Files.writeString(Path.of("target/research-review-live.json"),write(rows));}
            }
        } finally {executor.shutdownNow();}
        assertThat(rows).hasSize(8);assertThat(rows).allMatch(r->"COMPLETED".equals(r.get("execution")));
    }
}
