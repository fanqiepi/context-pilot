package io.github.fanqiepi.contextpilot.projectsource;

import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none",
        "PROJECT_SOURCE_ENABLED=true","contextpilot.document.processing.enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ProjectSourcePersistenceTests {
    static volatile String remoteCommit="a".repeat(40);
    static volatile long readDelay;
    static final java.util.concurrent.atomic.AtomicInteger remoteReads=new java.util.concurrent.atomic.AtomicInteger();
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    static final ProjectSourceMcpServer SERVER=new ProjectSourceMcpServer(0,new ProjectSourcePort(){
        public String resolveRef(String ref,Instant end){return remoteCommit;}
        public ProjectDocumentSnapshot readDocument(String commit,String path,Instant end){
            remoteReads.incrementAndGet();
            if(path.equals("missing.md"))throw new ProjectSourceException("SOURCE_PATH_NOT_FOUND");
            try{Thread.sleep(readDelay);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ProjectSourceException("SOURCE_INTERRUPTED");}
            String text="公开资料 "+path;byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            return new ProjectDocumentSnapshot(ProjectSourcePolicy.REPOSITORY,commit,path,"text/markdown",bytes.length,
                    ProjectSourcePolicy.hash(bytes),Instant.now().toString(),text);
        }
    });
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("PROJECT_SOURCE_MCP_URL",()->"http://127.0.0.1:"+SERVER.port());
        r.add("contextpilot.storage.root",()->"target/project-source-test-uploads");
    }
    @AfterAll static void closeServer(){SERVER.close();}
    @Autowired ProjectSourcePreviewService previews;
    @Autowired ProjectImportProposalService proposals;
    @Autowired ProjectImportMapper imports;
    @Autowired ActionRequestService actions;
    @Autowired KnowledgeBaseService bases;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectPreviewMapper previewMapper;
    @Autowired io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider subjects;
    @Autowired io.github.fanqiepi.contextpilot.document.StorageService storage;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @BeforeEach void resetRemote(){remoteCommit="a".repeat(40);readDelay=0;}

    @Test void movedTagDoesNotChangeConfirmedSnapshot() throws Exception {
        var preview=preview();UUID base=base();int reads=remoteReads.get();
        remoteCommit="b".repeat(40);
        var proposal=proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()));
        assertThat(actions.confirm(proposal.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(imports.origin(imports.results(proposal.id()).getFirst()).commit()).isEqualTo("a".repeat(40));
        assertThat(remoteReads).hasValue(reads);
    }
    @Test void failedFileIsIdentifiedWithoutPublishingPartialPreview() throws Exception {
        var created=previews.create(new ProjectSourcePreviewService.CreateRequest("spring-ai","v1.0.0",
                List.of("README.md","missing.md"),UUID.randomUUID()));
        for(int i=0;i<200 && !"FAILED".equals(previews.get(created.id()).status());i++)Thread.sleep(25);
        var failed=previews.get(created.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("SOURCE_PATH_NOT_FOUND");
        assertThat(failed.errorPath()).isEqualTo("missing.md");
        assertThat(failed.items()).isEmpty();
        assertThat(previewMapper.items(created.id())).isEmpty();
    }
    @Test void restartRecoveryFencesLatePreviewWrite() throws Exception {
        readDelay=700;
        var created=previews.create(new ProjectSourcePreviewService.CreateRequest("spring-ai","v1.0.0",List.of("README.md"),UUID.randomUUID()));
        for(int i=0;i<100 && !"RUNNING".equals(previews.get(created.id()).status());i++)Thread.sleep(10);
        previews.recover();
        Thread.sleep(1200);
        assertThat(previews.get(created.id()).status()).isEqualTo("FAILED");
        assertThat(previews.get(created.id()).errorCode()).isEqualTo("SOURCE_PROCESS_RESTARTED");
        assertThat(previewMapper.items(created.id())).isEmpty();
    }
    @Test void hardDeadlineRejectsLateResults() throws Exception {
        readDelay=1500;
        var bounded=new ProjectSourcePreviewService(previewMapper,subjects,storage,tx,true,
                "http://127.0.0.1:"+SERVER.port(),java.time.Duration.ofMillis(500));
        try {
            var created=bounded.create(new ProjectSourcePreviewService.CreateRequest("spring-ai","v1.0.0",List.of("README.md"),UUID.randomUUID()));
            Thread.sleep(2200);
            var current=bounded.get(created.id());
            assertThat(current.status()).isEqualTo("FAILED");
            assertThat(current.errorCode()).isEqualTo("SOURCE_TIMEOUT");
            assertThat(previewMapper.items(created.id())).isEmpty();
        } finally {bounded.close();}
    }

    @Test void previewAndProposalHaveNoDocumentSideEffectsAndConcurrentConfirmationIsIdempotent() throws Exception {
        var request=new ProjectSourcePreviewService.CreateRequest("spring-ai","v1.0.0",List.of("README.md","docs/guide.md"),UUID.randomUUID());
        var preview=ready(previews.create(request).id());
        assertThat(previews.create(request).id()).isEqualTo(preview.id());
        UUID base=base();
        var proposalRequest=new ProjectImportProposalService.Request(base,UUID.randomUUID());
        var proposal=proposals.propose(preview.id(),proposalRequest);
        assertThat(proposals.propose(preview.id(),proposalRequest).id()).isEqualTo(proposal.id());
        assertThat(count(base)).isZero();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var results=pool.invokeAll(Collections.<Callable<ActionRequestResponse>>nCopies(4,()->actions.confirm(proposal.id())));
            for(var result:results)assertThat(result.get().status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        }
        assertThat(count(base)).isEqualTo(2);
        assertThat(imports.results(proposal.id())).hasSize(2);
        for(UUID id:imports.results(proposal.id()))assertThat(imports.origin(id).commit()).isEqualTo("a".repeat(40));
        var repeated=proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()));
        assertThat(actions.confirm(repeated.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(count(base)).isEqualTo(2);
    }
    @Test void rejectedAndExpiredProposalsDoNotImport() throws Exception {
        var preview=preview();UUID base=base();
        var rejected=proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()));
        actions.reject(rejected.id());assertThat(actions.confirm(rejected.id()).status()).isEqualTo(ActionRequestStatus.REJECTED);
        jdbc.update("UPDATE project_source_preview SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",preview.id());
        assertThatThrownBy(()->proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()))).isInstanceOf(ConflictException.class);
        assertThat(count(base)).isZero();
    }
    @Test void sameSnapshotCanBeImportedIntoTwoIsolatedKnowledgeBases() throws Exception {
        var preview=preview();UUID first=base(),second=base();
        var left=proposals.propose(preview.id(),new ProjectImportProposalService.Request(first,UUID.randomUUID()));
        var right=proposals.propose(preview.id(),new ProjectImportProposalService.Request(second,UUID.randomUUID()));
        assertThat(actions.confirm(left.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(actions.confirm(right.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(imports.results(left.id())).doesNotContainAnyElementsOf(imports.results(right.id()));
        assertThat(count(first)).isEqualTo(1);assertThat(count(second)).isEqualTo(1);
    }
    @Test void deletedDocumentIdentityCannotBeResurrected() throws Exception {
        var preview=preview();UUID base=base();
        var first=proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()));actions.confirm(first.id());
        UUID document=imports.results(first.id()).getFirst();
        jdbc.update("UPDATE source_document SET deleted=1 WHERE id=?",document);
        var second=proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()));
        assertThat(actions.confirm(second.id()).status()).isEqualTo(ActionRequestStatus.FAILED);
        assertThat(imports.results(second.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT deleted FROM source_document WHERE id=?",Integer.class,document)).isEqualTo(1);
    }
    @Test void failedBatchRegistrationRollsBackDocumentsAndExecutionClaim() throws Exception {
        var preview=preview();UUID base=base();
        var proposal=proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()));
        jdbc.execute("CREATE FUNCTION fail_source_origin() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        jdbc.execute("CREATE TRIGGER fail_source_origin BEFORE INSERT ON project_document_origin FOR EACH ROW EXECUTE FUNCTION fail_source_origin()");
        try { assertThatThrownBy(()->actions.confirm(proposal.id())).isInstanceOf(RuntimeException.class); }
        finally {jdbc.execute("DROP TRIGGER fail_source_origin ON project_document_origin");jdbc.execute("DROP FUNCTION fail_source_origin()");}
        assertThat(count(base)).isZero();
        assertThat(actions.get(proposal.id()).status()).isEqualTo(ActionRequestStatus.PENDING_CONFIRMATION);
        assertThat(actions.confirm(proposal.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
    }
    private UUID base(){return bases.create(new KnowledgeBaseCreateRequest("source-"+UUID.randomUUID(),null)).id();}
    private int count(UUID base){return jdbc.queryForObject("SELECT count(*) FROM source_document WHERE knowledge_base_id=?",Integer.class,base);}
    private ProjectSourcePreviewService.PreviewResponse preview() throws Exception {
        return ready(previews.create(new ProjectSourcePreviewService.CreateRequest("spring-ai","v1.0.0",List.of("README.md"),UUID.randomUUID())).id());
    }
    private ProjectSourcePreviewService.PreviewResponse ready(UUID id) throws Exception {
        for(int attempt=0;attempt<200;attempt++) {
            var value=previews.get(id);
            if("READY".equals(value.status()))return value;
            assertThat(value.status()).as(value.errorCode()).isNotEqualTo("FAILED");
            Thread.sleep(25);
        }
        throw new AssertionError("Preview did not finish");
    }
}
