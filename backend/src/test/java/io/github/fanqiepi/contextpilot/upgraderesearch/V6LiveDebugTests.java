package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.researchreport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** Opt-in debug evidence only; never included in frozen final semantic scores. */
@SpringBootTest(properties={"spring.ai.model.chat=deepseek","spring.ai.model.embedding=openai",
    "spring.ai.vectorstore.type=pgvector","contextpilot.document.processing.enabled=true",
    "contextpilot.upgrade-research.enabled=true","contextpilot.storage.root=target/v6-debug-uploads"})
@ActiveProfiles("local-secrets")
@Testcontainers
@EnabledIfSystemProperty(named="contextpilot.evaluation.v6-debug",matches="true")
class V6LiveDebugTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName
        .parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("context_pilot").withUsername("context_pilot").withPassword("context_pilot_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;
    @Autowired UpgradeResearchStartService starts;
    @Autowired UpgradeQueryService query;
    @Autowired ResearchReportProposalService proposals;
    @Autowired ResearchReportMapper reports;
    @Autowired ActionRequestService actions;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean UpgradeResearchModelGateway model;

    @Test @Timeout(300) void realModelRetrievalReleaseAndConfirmation() throws Exception {
        var output=new LinkedHashMap<String,Object>();output.put("mode","LIVE_DEBUG_NOT_FINAL_EVALUATION");
        output.put("startedAt",OffsetDateTime.now().toString());
        Path artifact=Path.of("target/v6-live-debug.json");
        org.mockito.Mockito.doAnswer(invocation->{
            var response=(org.springframework.ai.chat.model.ChatResponse)invocation.callRealMethod();
            if(response.getResult().getOutput().getToolCalls().isEmpty())output.put("debugFinalOutput",response.getResult().getOutput().getText());
            return response;
        }).when(model).call(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean());
        try {
            UUID kb=bases.create(new KnowledgeBaseCreateRequest("V6 public debug",null)).id();
            byte[] source=Files.readAllBytes(Path.of("../evals/corpora/v6-upgrade-debug-v1/legacy-worker.md"));
            UUID doc=documents.upload(kb,new MockMultipartFile("file","legacy-worker.md","text/markdown",source)).id();
            long until=System.nanoTime()+Duration.ofSeconds(80).toNanos();
            while(documents.list(kb).stream().anyMatch(d->d.status()!=DocumentStatus.SUCCEEDED)){
                assertThat(System.nanoTime()).as("document processing deadline").isLessThan(until);Thread.sleep(250);
            }
            var request=new UpgradeStartRequest(UUID.randomUUID(),kb,List.of(doc),"spring-ai",List.of("v2.0.0"),
                "旧工作进程按 FIFO 调度，不支持任务优先级。请检索旧方案中的 scheduling 说明，并核查指定 Spring AI 发布记录能否支持改变任务调度的升级建议；没有依据时明确标注缺口。",null);
            var run=starts.start(request,"v6-live-debug-"+UUID.randomUUID()).run();
            UpgradeQueryService.View view;
            do {Thread.sleep(300);view=query.get(run.getId());}while(!view.run().getStatus().terminal());
            output.put("run",view.run());output.put("tools",view.tools());output.put("modelCalls",view.modelCalls());
            // Store hashes/metadata only for third-party release text; no release body redistribution.
            output.put("sources",view.sources().stream().map(s->Map.of("sourceId",s.sourceId(),"type",s.sourceType(),
                "hash",s.contentHash(),"truncated",s.truncated(),"fetchedAt",s.fetchedAt().toString())).toList());
            assertThat(view.run().getStatus()).isIn(UpgradeRunStatus.SUCCEEDED,UpgradeRunStatus.PARTIAL,UpgradeRunStatus.REFUSED);
            assertThat(view.run().getModelCalls()).isBetween(1,4);
            assertThat(view.run().getToolRequests()).isBetween(1,8);
            if(view.run().getDraft()!=null){
                var proposal=proposals.propose(run.getId(),new ReportSaveRequest(UUID.randomUUID(),"公开调试报告",null));
                assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
                assertThat(query.get(run.getId()).saveActionId()).isEqualTo(proposal.id());
                assertThat(actions.confirm(proposal.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
                output.put("reportConfirmed",true);
            }
            output.put("status","ENGINEERING_GATES_PASSED_SEMANTICS_UNREVIEWED");
        }catch(Exception|AssertionError error){output.put("status","FAILED");output.put("errorType",error.getClass().getSimpleName());throw error;}
        finally{output.put("finishedAt",OffsetDateTime.now().toString());Files.writeString(artifact,json.writeValueAsString(output));}
    }
}
