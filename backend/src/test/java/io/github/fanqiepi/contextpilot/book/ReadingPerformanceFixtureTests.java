package io.github.fanqiepi.contextpilot.book;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.github.fanqiepi.contextpilot.document.StorageService;
import io.github.fanqiepi.contextpilot.readingstate.ReadingStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, disposable long-book session. Never uses the daily or paid-evaluation database. */
@EnabledIfSystemProperty(named="reading.performance",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none",
        "contextpilot.reading.live-enabled=false","contextpilot.storage.root=target/reading-performance-uploads",
        "contextpilot.task-workspace.enabled=false","contextpilot.upgrade-research.enabled=false"})
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class ReadingPerformanceFixtureTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired BookImportService imports;
    @Autowired BookParser parser;
    @Autowired BookService books;
    @Autowired BookMapper mapper;
    @Autowired ReadingStateService states;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test void longBookBrowserSession() throws Exception {
        // 9,600 short-chapter paragraphs + 400 in the final chapter: exactly 5M code points.
        // Unique paragraph prefixes catch wrong cursor/position restoration; no copyrighted text.
        var text=new StringBuilder(5_020_000);
        int ordinal=0;
        for(int chapter=1;chapter<=1000;chapter++) {
            text.append("第").append(chapter).append("章 合成容量样本\n");
            int count=chapter==1000?400:chapter<=609?10:9;
            for(int n=0;n<count;n++) {
                String prefix="段落%05d。".formatted(++ordinal);
                text.append(prefix).append("甲".repeat(500-prefix.length())).append('\n');
            }
        }
        byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);
        long started=System.nanoTime();
        var registration=imports.register(new BookImportService.Request(UUID.randomUUID(),"五百万字 · 长篇续读性能样本","项目生成，容量与定位验证，不用于语义评价","TXT_CN_CHAPTER_V1",null,null),"performance.txt",bytes);
        storage.store(registration.row().storageKey,new java.io.ByteArrayInputStream(bytes));
        imports.parsed(registration.row().ownerId,registration.row().id,parser.parse(bytes,BookParser.Rule.TXT_CN_CHAPTER_V1,null));
        long parseMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        started=System.nanoTime();
        var result=imports.confirm(registration.row().id,imports.preview(registration.row().id).previewHash());
        long confirmMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertThat(mapper.version(registration.row().ownerId,result.versionId()).characterCount()).isEqualTo(5_000_000);
        var chapter=books.chapters(result.versionId(),20,50).items().getLast();
        var firstPage=books.paragraphs(result.versionId(),chapter.id(),null,100,false);
        assertThat(firstPage.paragraphs()).hasSize(40);
        assertThat(firstPage.nextCursor()).isNotNull();
        var anchor=firstPage.paragraphs().get(9);
        states.resume(result.versionId(),new ReadingStateService.Resume(UUID.randomUUID(),0L,anchor.id(),7));
        var reads=new ArrayList<Double>();var writes=new ArrayList<Double>();
        for(int i=0;i<30;i++) {
            started=System.nanoTime();
            var page=books.paragraphs(result.versionId(),chapter.id(),firstPage.nextCursor(),100,false);
            reads.add((System.nanoTime()-started)/1_000_000.0);
            assertThat(page.paragraphs().getFirst().paragraphOrdinal()).isEqualTo(9641);
            var previous=states.get(result.versionId());
            started=System.nanoTime();
            states.resume(result.versionId(),new ReadingStateService.Resume(UUID.randomUUID(),previous.resumeRevision(),anchor.id(),7));
            writes.add((System.nanoTime()-started)/1_000_000.0);
            assertThat(states.get(result.versionId()).resumePosition().offset()).isEqualTo(7);
            assertThat(states.get(result.versionId()).readBoundary()).isZero();
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("mode","OFFLINE_ENGINEERING");report.put("recordedAt",java.time.Instant.now().toString());
        report.put("port",port);report.put("bookId",result.bookId());report.put("versionId",result.versionId());
        report.put("fixture",Map.of("characters",5_000_000,"chapters",1000,"paragraphs",ordinal,"finalChapterParagraphs",400,"sha256",ReadingText.hash(text.toString()),"resumeParagraph",9610,"resumeOffset",7));
        report.put("environment",Map.of("os",System.getProperty("os.name"),"java",System.getProperty("java.version"),"processors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),"postgres",jdbc.queryForObject("select version()",String.class)));
        report.put("parsePreviewMs",parseMs);report.put("confirmMs",confirmMs);
        report.put("paragraphSamplesMs",reads);report.put("positionWriteSamplesMs",writes);
        report.put("limitations",List.of("Service timings exclude HTTP/browser. Application and database already started.","Synthetic text, no AI calls, no reader feedback. Browser cache state is recorded separately."));
        var folder=Path.of(System.getProperty("reading.performance.directory","target/reading-performance"));Files.createDirectories(folder);
        Files.writeString(folder.resolve("fixture.json"),ReadingText.json(report));
        var done=folder.resolve("done");long until=System.nanoTime()+TimeUnit.MINUTES.toNanos(60);
        while(!Files.exists(done) && System.nanoTime()<until)Thread.sleep(500);
        assertThat(Files.exists(done)).as("Performance session must be explicitly closed").isTrue();
        var finalState=states.get(result.versionId());
        Files.writeString(folder.resolve("final-state.json"),ReadingText.json(finalState));
        assertThat(finalState.readBoundary()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from reading_task",Long.class)).isZero();
    }
}
